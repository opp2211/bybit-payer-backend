package ru.maltsev.bybitpayerbackend.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.maltsev.bybitpayerbackend.ai.config.AiChatAgentProperties;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatSessionEntity;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatSessionStatus;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatStep;
import ru.maltsev.bybitpayerbackend.ai.repository.AiChatSessionRepository;
import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitChatMessage;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitChatService;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceAccessService;

@ExtendWith(MockitoExtension.class)
class AiChatAgentServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-23T08:00:00Z");

    @Mock
    private AiChatSessionRepository sessionRepository;
    @Mock
    private WithdrawalRequestRepository withdrawalRepository;
    @Mock
    private EmailReceiptCheckRepository receiptCheckRepository;
    @Mock
    private WorkspaceAccessService workspaceAccessService;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private AuditService auditService;
    @Mock
    private BybitChatService chatService;
    @Mock
    private OpenAiChatAgentClient openAiClient;
    @Mock
    private WithdrawalEventService eventService;

    private AiChatAgentProperties properties;
    private AiChatAgentService service;

    @BeforeEach
    void setUp() {
        properties = new AiChatAgentProperties();
        service = new AiChatAgentService(
                properties,
                sessionRepository,
                withdrawalRepository,
                receiptCheckRepository,
                workspaceAccessService,
                currentUserService,
                auditService,
                chatService,
                openAiClient,
                eventService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void restartsExistingSessionWhenNewBybitOrderIsBound() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace, "order-new");
        AiChatSessionEntity session = staleSession(workspace, withdrawal, "order-old");

        when(sessionRepository.findByWithdrawalRequest(withdrawal)).thenReturn(Optional.of(session));
        when(openAiClient.configured()).thenReturn(true);
        when(sessionRepository.save(any(AiChatSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.sendAgentMessages(eq(workspace), eq(withdrawal), anyList())).thenReturn(true);

        service.startForOrder(workspace, withdrawal);

        assertThat(session.getBybitOrderId()).isEqualTo("order-new");
        assertThat(session.isEnabled()).isTrue();
        assertThat(session.getStatus()).isEqualTo(AiChatSessionStatus.WAITING_COUNTERPARTY);
        assertThat(session.getCurrentStep()).isEqualTo(AiChatStep.SENDER_FIRST_PARTY);
        assertThat(session.isAutoReceiptEnabled()).isFalse();
        assertThat(session.isRequiredReceiptEmail()).isFalse();
        assertThat(session.isOptionalReceiptEmail()).isFalse();
        assertThat(session.getSenderFirstPartyConfirmed()).isNull();
        assertThat(session.getPayerBankConfirmed()).isNull();
        assertThat(session.getPayerBankName()).isNull();
        assertThat(session.getThirdPartyTransferConfirmed()).isNull();
        assertThat(session.getFinalWarningConfirmed()).isNull();
        assertThat(session.getRequisitesSentAt()).isNull();
        assertThat(session.getOperatorRequiredAt()).isNull();
        assertThat(session.getLastProcessedMessageId()).isNull();
        assertThat(session.getLastProcessedMessageCreatedAt()).isNull();
        assertThat(session.getLastReceiptCheckIdHandled()).isNull();
        assertThat(session.getUnclearRepliesCount()).isZero();
        assertThat(session.getCancellationRepliesCount()).isZero();
        assertThat(session.getPaidWithoutReceiptRepliesCount()).isZero();
        assertThat(session.getSuggestedMessagesJson()).isNull();
        assertThat(session.getLastDecisionSummary()).isNull();
        assertThat(session.getCreatedAt()).isEqualTo(NOW);
        assertThat(session.getUpdatedAt()).isEqualTo(NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService).sendAgentMessages(eq(workspace), eq(withdrawal), messagesCaptor.capture());
        assertThat(messagesCaptor.getValue())
                .hasSize(2)
                .first()
                .isEqualTo("Привет");
        verify(eventService).add(
                withdrawal,
                WithdrawalEventType.AI_CHAT_STARTED,
                "AI chat agent restarted for new Bybit order"
        );
    }

    @Test
    void doesNotRestartExistingSessionForSameBybitOrder() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace, "order-1");
        AiChatSessionEntity session = staleSession(workspace, withdrawal, "order-1");

        when(sessionRepository.findByWithdrawalRequest(withdrawal)).thenReturn(Optional.of(session));

        service.startForOrder(workspace, withdrawal);

        verify(sessionRepository, never()).save(any());
        verifyNoInteractions(chatService, openAiClient, eventService);
    }

    @Test
    void storesDetailedOpenAiFailureAsWithdrawalWarning() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace, "order-1");
        withdrawal.setRequireSenderFirstParty(false);
        AiChatSessionEntity session = new AiChatSessionEntity();
        session.setWorkspace(workspace);
        session.setWithdrawalRequest(withdrawal);
        session.setBybitOrderId("order-1");
        session.setEnabled(true);
        session.setStatus(AiChatSessionStatus.WAITING_COUNTERPARTY);
        session.setCurrentStep(AiChatStep.PAYER_BANK);
        session.setCreatedAt(NOW);
        session.setUpdatedAt(NOW);

        String reason = "OpenAI unavailable: OpenAI HTTP 403 [unsupported_country_region_territory]: Country, region, or territory not supported";
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(anyCollection())).thenReturn(List.of(session));
        when(chatService.getCounterpartyTextMessages(workspace, withdrawal)).thenReturn(List.of(new BybitChatMessage(
                "message-1",
                "я буду платить с Т-Банка",
                "counterparty",
                1,
                NOW.plusSeconds(1),
                "str",
                "order-1",
                "message-uuid",
                "buyer",
                "user"
        )));
        when(openAiClient.configured()).thenReturn(true);
        when(openAiClient.decide(eq(session), any(AiChatDecisionRequest.class)))
                .thenThrow(new OpenAiUnavailableException("OpenAI HTTP 403 [unsupported_country_region_territory]: Country, region, or territory not supported"));

        service.pollActiveSessions();

        assertThat(session.isEnabled()).isFalse();
        assertThat(session.getStatus()).isEqualTo(AiChatSessionStatus.OPERATOR_REQUIRED);
        assertThat(session.getCurrentStep()).isEqualTo(AiChatStep.OPERATOR_HANDOFF);
        assertThat(session.getLastDecisionSummary()).isEqualTo(reason);
        assertThat(withdrawal.isAttentionRequired()).isTrue();
        assertThat(withdrawal.getLastWarning()).isEqualTo(reason);
        assertThat(session.getLastProcessedMessageId()).isEqualTo("message-1");
        verify(eventService).add(withdrawal, WithdrawalEventType.AI_CHAT_OPERATOR_REQUIRED, reason);
    }

    private WorkspaceEntity workspace() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setReceiptEmail("receipts@example.com");
        return workspace;
    }

    private WithdrawalRequestEntity withdrawal(WorkspaceEntity workspace, String bybitOrderId) {
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setWorkspace(workspace);
        withdrawal.setBybitOrderId(bybitOrderId);
        withdrawal.setStatus(WithdrawalStatus.PAYMENT_IN_PROGRESS);
        withdrawal.setPayerBankType(PayerBankType.ANY_BANK);
        withdrawal.setWithdrawalMethod(WithdrawalMethod.SBP);
        withdrawal.setRequireSenderFirstParty(true);
        withdrawal.setThirdPartyTransfer(true);
        return withdrawal;
    }

    private AiChatSessionEntity staleSession(
            WorkspaceEntity workspace,
            WithdrawalRequestEntity withdrawal,
            String bybitOrderId
    ) {
        AiChatSessionEntity session = new AiChatSessionEntity();
        session.setId(42L);
        session.setWorkspace(workspace);
        session.setWithdrawalRequest(withdrawal);
        session.setBybitOrderId(bybitOrderId);
        session.setEnabled(false);
        session.setStatus(AiChatSessionStatus.OPERATOR_REQUIRED);
        session.setCurrentStep(AiChatStep.OPERATOR_HANDOFF);
        session.setAutoReceiptEnabled(true);
        session.setRequiredReceiptEmail(true);
        session.setOptionalReceiptEmail(true);
        session.setSenderFirstPartyConfirmed(true);
        session.setPayerBankConfirmed(true);
        session.setPayerBankName("Т-Банк");
        session.setThirdPartyTransferConfirmed(true);
        session.setFinalWarningConfirmed(true);
        session.setRequisitesSentAt(NOW.minusSeconds(400));
        session.setOperatorRequiredAt(NOW.minusSeconds(300));
        session.setLastProcessedMessageId("old-message");
        session.setLastProcessedMessageCreatedAt(NOW.minusSeconds(200));
        session.setLastReceiptCheckIdHandled(7L);
        session.setUnclearRepliesCount(3);
        session.setCancellationRepliesCount(2);
        session.setPaidWithoutReceiptRepliesCount(1);
        session.setSuggestedMessagesJson("[\"old\"]");
        session.setSuggestedReason("old reason");
        session.setSuggestedAt(NOW.minusSeconds(100));
        session.setLastDecisionSummary("old summary");
        session.setCreatedAt(NOW.minusSeconds(500));
        session.setUpdatedAt(NOW.minusSeconds(50));
        return session;
    }
}
