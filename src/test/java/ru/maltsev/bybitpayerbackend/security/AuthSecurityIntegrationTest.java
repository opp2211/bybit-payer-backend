package ru.maltsev.bybitpayerbackend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import ru.maltsev.bybitpayerbackend.bybit.gateway.TestBybitGatewayConfiguration;
import ru.maltsev.bybitpayerbackend.common.logging.RequestLoggingFilter;
import ru.maltsev.bybitpayerbackend.security.config.SecurityConfig;
import ru.maltsev.bybitpayerbackend.security.dto.CsrfTokenResponse;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.user.repository.UserRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalEventEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalEventRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceMemberEntity;
import ru.maltsev.bybitpayerbackend.workspace.model.WorkspaceMemberRole;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceMemberRepository;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestBybitGatewayConfiguration.class)
class AuthSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private WithdrawalRequestRepository withdrawalRepository;

    @Autowired
    private WithdrawalEventRepository withdrawalEventRepository;

    @Autowired
    private WithdrawalEventService withdrawalEventService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void createPersistentLoginTable() {
        jdbcTemplate.execute("drop table if exists persistent_logins");
        jdbcTemplate.execute("""
                create table persistent_logins (
                    username varchar(64) not null,
                    series varchar(64) primary key,
                    token varchar(64) not null,
                    last_used timestamp not null
                )
                """);
    }

    @Test
    void rejectsProtectedApiWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(RequestLoggingFilter.REQUEST_ID_HEADER));
    }

    @Test
    void exposesCsrfTokenBeforeAuthentication() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void allowsSameOriginFramesForEmbeddedPdfViewer() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
    }

    @Test
    void exposesHealthEndpointWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void logsInAndRestoresAuthenticationFromPersistentCookie() throws Exception {
        MvcResult login = login("password")
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists(SecurityConfig.REMEMBER_ME_COOKIE))
                .andExpect(cookie().secure(SecurityConfig.REMEMBER_ME_COOKIE, true))
                .andReturn();

        Cookie rememberMeCookie = login.getResponse().getCookie(SecurityConfig.REMEMBER_ME_COOKIE);
        assertThat(rememberMeCookie).isNotNull();

        mockMvc.perform(get("/api/auth/me").cookie(rememberMeCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator"));
    }

    @Test
    void rejectsInvalidCredentials() throws Exception {
        login("wrong-password")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesPersistentCookie() throws Exception {
        MvcResult login = login("password")
                .andExpect(status().isNoContent())
                .andReturn();

        Cookie rememberMeCookie = login.getResponse().getCookie(SecurityConfig.REMEMBER_ME_COOKIE);
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(rememberMeCookie).isNotNull();
        assertThat(session).isNotNull();

        mockMvc.perform(post("/api/auth/logout")
                        .with(csrf())
                        .session(session)
                        .cookie(rememberMeCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").cookie(rememberMeCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void servesOnlyRecentSoundEventsForTheRequestedWorkspace() throws Exception {
        UserEntity operator = userRepository.findByUsernameNormalized("operator").orElseThrow();
        Instant now = Instant.now();
        WorkspaceEntity workspace = workspace("DAA0001", operator, now);
        WorkspaceEntity otherWorkspace = workspace("DAA0002", operator, now);
        WithdrawalRequestEntity withdrawal = withdrawal(workspace, now);
        WithdrawalRequestEntity otherWithdrawal = withdrawal(otherWorkspace, now);

        addMembership(workspace, operator, now);

        addEvent(withdrawal, WithdrawalEventType.ORDER_FOUND, now.minusSeconds(10));
        addEvent(withdrawal, WithdrawalEventType.ORDER_CANCELLED, now.minusSeconds(5));
        addEvent(withdrawal, WithdrawalEventType.ATTENTION_REQUIRED, now.minusSeconds(600));
        addEvent(withdrawal, WithdrawalEventType.CHAT_MESSAGE_SENT, now);
        addEvent(otherWithdrawal, WithdrawalEventType.ORDER_APPEAL_STARTED, now);

        mockMvc.perform(get("/api/workspaces/{workspacePublicId}/withdrawals/sound-events", workspace.getPublicId())
                        .with(user("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].eventType").value("ORDER_CANCELLED"))
                .andExpect(jsonPath("$[1].eventType").value("ORDER_FOUND"));

        mockMvc.perform(get("/api/workspaces/{workspacePublicId}/withdrawals/sound-events", otherWorkspace.getPublicId())
                        .with(user("operator")))
                .andExpect(status().isForbidden());
    }

    @Test
    void streamsCommittedUpdatesOnlyForAnAccessibleWorkspace() throws Exception {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        WithdrawalRequestEntity withdrawal = transactions.execute(status -> {
            UserEntity operator = userRepository.findByUsernameNormalized("operator").orElseThrow();
            Instant now = Instant.now();
            WorkspaceEntity workspace = workspace("DAA0003", operator, now);
            addMembership(workspace, operator, now);
            workspace("DAA0004", operator, now);
            WithdrawalRequestEntity entity = withdrawal(workspace, now);
            entity.setPublicId("DAA0005");
            return entity;
        });
        assertThat(withdrawal).isNotNull();
        MockHttpSession session = new MockHttpSession();
        String updatesUrl = "/api/workspaces/{workspacePublicId}/withdrawals/updates";

        try {
            mockMvc.perform(get(updatesUrl, "DAA0003").accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get(updatesUrl, "DAA0004").accept(MediaType.TEXT_EVENT_STREAM).with(user("operator")))
                    .andExpect(status().isForbidden());

            MvcResult stream = mockMvc.perform(get(updatesUrl, "DAA0003")
                            .accept(MediaType.TEXT_EVENT_STREAM).with(user("operator")).session(session))
                    .andExpect(request().asyncStarted())
                    .andExpect(header().string("X-Accel-Buffering", "no"))
                    .andReturn();
            assertThat(stream.getResponse().getContentAsString()).contains("event:ready");

            transactions.executeWithoutResult(status -> {
                WithdrawalRequestEntity entity = withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
                withdrawalEventService.add(entity, WithdrawalEventType.ORDER_CANCELLED, "Rolled back");
                status.setRollbackOnly();
            });
            transactions.executeWithoutResult(status -> {
                WithdrawalRequestEntity entity = withdrawalRepository.findById(withdrawal.getId()).orElseThrow();
                withdrawalEventService.add(entity, WithdrawalEventType.ORDER_FOUND, "Committed");
            });

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(stream.getResponse().getContentAsString())
                            .contains("event:withdrawal", "\"withdrawalPublicId\":\"DAA0005\"",
                                    "\"eventType\":\"ORDER_FOUND\"", "\"soundEvent\":true")
                            .doesNotContain("ORDER_CANCELLED", "Rolled back")
            );
        } finally {
            session.invalidate();
            transactions.executeWithoutResult(status -> {
                jdbcTemplate.update("delete from withdrawal_events where withdrawal_request_id = ?", withdrawal.getId());
                jdbcTemplate.update("delete from withdrawal_requests where id = ?", withdrawal.getId());
                jdbcTemplate.update("delete from workspace_members where workspace_id = ?", withdrawal.getWorkspace().getId());
                jdbcTemplate.update("delete from workspaces where public_id in (?, ?)", "DAA0003", "DAA0004");
            });
        }
    }

    private void addMembership(WorkspaceEntity workspace, UserEntity user, Instant now) {
        WorkspaceMemberEntity membership = new WorkspaceMemberEntity();
        membership.setWorkspace(workspace);
        membership.setUser(user);
        membership.setRole(WorkspaceMemberRole.OWNER);
        membership.setCreatedAt(now);
        workspaceMemberRepository.save(membership);
    }

    private WorkspaceEntity workspace(String id, UserEntity owner, Instant now) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setPublicId(id);
        workspace.setName(id);
        workspace.setOwner(owner);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        return workspaceRepository.save(workspace);
    }

    private WithdrawalRequestEntity withdrawal(WorkspaceEntity workspace, Instant now) {
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setWorkspace(workspace);
        withdrawal.setAmountMinRub(BigDecimal.TEN);
        withdrawal.setAmountMaxRub(BigDecimal.TEN);
        withdrawal.setWithdrawalMethod(WithdrawalMethod.SBP);
        withdrawal.setPayerBankType(PayerBankType.TBANK_AUTO);
        withdrawal.setStatus(WithdrawalStatus.NEW);
        withdrawal.setCreatedAt(now);
        return withdrawalRepository.save(withdrawal);
    }

    private void addEvent(WithdrawalRequestEntity withdrawal, WithdrawalEventType type, Instant createdAt) {
        WithdrawalEventEntity event = new WithdrawalEventEntity();
        event.setWithdrawalRequest(withdrawal);
        event.setEventType(type);
        event.setMessage(type.name());
        event.setCreatedAt(createdAt);
        withdrawalEventRepository.save(event);
    }

    private ResultActions login(String password) throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        CsrfTokenResponse csrfToken = objectMapper.readValue(
                csrfResult.getResponse().getContentAsByteArray(),
                CsrfTokenResponse.class
        );
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        return mockMvc.perform(post("/api/auth/login")
                .session(session)
                .header(csrfToken.headerName(), csrfToken.token())
                .param("username", "operator")
                .param("password", password));
    }
}
