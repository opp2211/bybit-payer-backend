package ru.maltsev.bybitpayerbackend.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.ai.service.OpenAiChatAgentClient;
import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.service.PublicIdGenerator;
import ru.maltsev.bybitpayerbackend.receipt.service.TinkoffReceiptMailService;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.user.repository.UserRepository;
import ru.maltsev.bybitpayerbackend.user.service.UserNormalizer;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceMemberRepository;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;

class WorkspaceServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");

    private WorkspaceRepository workspaceRepository;
    private CurrentUserService currentUserService;
    private WorkspaceAccessService accessService;
    private AuditService auditService;
    private OpenAiChatAgentClient openAiClient;
    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        currentUserService = mock(CurrentUserService.class);
        accessService = mock(WorkspaceAccessService.class);
        auditService = mock(AuditService.class);
        openAiClient = mock(OpenAiChatAgentClient.class);
        service = new WorkspaceService(
                workspaceRepository,
                mock(WorkspaceMemberRepository.class),
                mock(UserRepository.class),
                currentUserService,
                accessService,
                mock(WorkspaceSecretService.class),
                mock(PublicIdGenerator.class),
                mock(UserNormalizer.class),
                mock(BybitCredentialsContext.class),
                mock(BybitGateway.class),
                mock(TinkoffReceiptMailService.class),
                auditService,
                openAiClient,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void allowsWorkspaceMemberToEnableAgentAndWritesAuditEvent() {
        UserEntity member = user(1L, "member");
        WorkspaceEntity workspace = workspace(user(2L, "owner"));
        when(currentUserService.currentUser()).thenReturn(member);
        when(accessService.getAccessibleWorkspace("ABC1234", member)).thenReturn(workspace);
        when(openAiClient.configured()).thenReturn(true);

        var response = service.updateAiChatAgent("ABC1234", true);

        assertThat(response.aiChatAgentEnabled()).isTrue();
        assertThat(response.currentUserRole()).isEqualTo("MEMBER");
        assertThat(workspace.getUpdatedAt()).isEqualTo(NOW);
        verify(workspaceRepository).save(workspace);
        verify(auditService).add(
                member,
                workspace,
                "WORKSPACE_AI_CHAT_ENABLED",
                "WORKSPACE",
                "ABC1234",
                null
        );
    }

    @Test
    void rejectsEnablingAgentWithoutOpenAiApiKey() {
        UserEntity member = user(1L, "member");
        WorkspaceEntity workspace = workspace(user(2L, "owner"));
        when(currentUserService.currentUser()).thenReturn(member);
        when(accessService.getAccessibleWorkspace("ABC1234", member)).thenReturn(workspace);
        when(openAiClient.configured()).thenReturn(false);

        assertThatThrownBy(() -> service.updateAiChatAgent("ABC1234", true))
                .isInstanceOf(BusinessException.class)
                .hasMessage("OpenAI API key is not configured");

        assertThat(workspace.isAiChatAgentEnabled()).isFalse();
        verify(workspaceRepository, never()).save(workspace);
        verify(auditService, never()).add(member, workspace, "WORKSPACE_AI_CHAT_ENABLED", "WORKSPACE", "ABC1234", null);
    }

    private WorkspaceEntity workspace(UserEntity owner) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(10L);
        workspace.setPublicId("ABC1234");
        workspace.setName("Workspace");
        workspace.setOwner(owner);
        workspace.setEnabled(true);
        workspace.setAiChatAgentEnabled(false);
        workspace.setCreatedAt(NOW.minusSeconds(60));
        workspace.setUpdatedAt(NOW.minusSeconds(60));
        return workspace;
    }

    private UserEntity user(Long id, String username) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setPublicId(username.equals("owner") ? "OWNER01" : "MEMBER1");
        user.setUsername(username);
        return user;
    }
}
