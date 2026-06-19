package ru.maltsev.bybitpayerbackend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import ru.maltsev.bybitpayerbackend.bybit.gateway.TestBybitGatewayConfiguration;
import ru.maltsev.bybitpayerbackend.security.config.SecurityConfig;
import ru.maltsev.bybitpayerbackend.security.dto.CsrfTokenResponse;

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
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exposesCsrfTokenBeforeAuthentication() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void logsInAndRestoresAuthenticationFromPersistentCookie() throws Exception {
        MvcResult login = login("password")
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists(SecurityConfig.REMEMBER_ME_COOKIE))
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
