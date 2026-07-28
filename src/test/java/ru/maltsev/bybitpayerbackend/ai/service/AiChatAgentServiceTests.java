package ru.maltsev.bybitpayerbackend.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import ru.maltsev.bybitpayerbackend.ai.config.AiChatAgentProperties;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatSessionEntity;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatAction;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatAgentMode;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatConfirmation;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatSessionStatus;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatStep;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionBankType;
import ru.maltsev.bybitpayerbackend.ai.model.AiPaymentClaim;
import ru.maltsev.bybitpayerbackend.ai.repository.AiChatSessionRepository;
import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageContentResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageContentType;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageSenderType;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitChatService;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;
import ru.maltsev.bybitpayerbackend.receipt.model.ReceiptVerificationStatus;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceAccessService;

class AiChatAgentServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-24T03:00:00Z");
    private static final String FINAL_WARNING = "Если отправите перевод не в тот банк, деньги могут быть потеряны, "
            + "и я не смогу помочь вернуть их. Пожалуйста, будьте внимательны.";

    private AiChatSessionRepository sessionRepository;
    private WithdrawalRequestRepository withdrawalRepository;
    private EmailReceiptCheckRepository receiptCheckRepository;
    private WorkspaceAccessService workspaceAccessService;
    private CurrentUserService currentUserService;
    private AuditService auditService;
    private BybitChatService chatService;
    private OpenAiChatAgentClient openAiClient;
    private AiChatPromptProvider promptProvider;
    private WithdrawalEventService eventService;
    private JdbcTemplate jdbcTemplate;
    private AiChatAgentService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(AiChatSessionRepository.class);
        withdrawalRepository = mock(WithdrawalRequestRepository.class);
        receiptCheckRepository = mock(EmailReceiptCheckRepository.class);
        workspaceAccessService = mock(WorkspaceAccessService.class);
        currentUserService = mock(CurrentUserService.class);
        auditService = mock(AuditService.class);
        chatService = mock(BybitChatService.class);
        openAiClient = mock(OpenAiChatAgentClient.class);
        promptProvider = mock(AiChatPromptProvider.class);
        eventService = mock(WithdrawalEventService.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        AiChatAgentProperties properties = new AiChatAgentProperties();
        properties.setInactivityReminderDelay(Duration.ofMinutes(5));
        properties.setPaymentVerificationReminderDelay(Duration.ofSeconds(90));
        properties.setMaxContextMessages(29);
        properties.setRetainedContextMessages(20);

        when(openAiClient.configured()).thenReturn(true);
        when(promptProvider.systemPrompt()).thenReturn("system prompt");
        when(sessionRepository.save(any(AiChatSessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.sendAgentMessages(any(), any(), any())).thenReturn(true);
        when(receiptCheckRepository.findByWithdrawalRequest_IdOrderByCreatedAtDescIdDesc(any()))
                .thenReturn(List.of());
        when(receiptCheckRepository
                .findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
                        any(), any(), eq(ReceiptVerificationStatus.FAILED)
                ))
                .thenReturn(Optional.empty());
        when(receiptCheckRepository
                .findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
                        any(), any(), eq(ReceiptVerificationStatus.VERIFIED)
                ))
                .thenReturn(Optional.empty());

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
                promptProvider,
                eventService,
                jdbcTemplate,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void startsWithHelloAndDoesNotReactToOwnMessages() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        when(sessionRepository.findByWithdrawalRequestAndBybitOrderId(
                withdrawal,
                withdrawal.getBybitOrderId()
        )).thenReturn(Optional.empty());
        List<ChatMessageLogResponse> chatWithHello = List.of(message(
                "bot-hello", ChatMessageSenderType.BOT, "Привет", NOW.minusSeconds(1)
        ));
        when(chatService.getMessages(workspace, withdrawal))
                .thenReturn(List.of())
                .thenReturn(chatWithHello);
        when(openAiClient.decide(any(), any())).thenReturn(decision(
                AiChatAction.SEND_MESSAGES,
                List.of("Привет! Подскажите, пожалуйста, с какого банка будете оплачивать?"),
                "",
                "Спросил банк отправителя"
        ));

        service.startForOrder(workspace, withdrawal);

        ArgumentCaptor<AiChatSessionEntity> sessionCaptor = ArgumentCaptor.forClass(AiChatSessionEntity.class);
        verify(sessionRepository, times(2)).save(sessionCaptor.capture());
        AiChatSessionEntity session = sessionCaptor.getValue();
        assertThat(session.getMode()).isEqualTo(AiChatAgentMode.ENABLED);
        verify(chatService).sendAgentMessages(workspace, withdrawal, List.of("Привет"));
        verify(chatService).sendAgentMessages(
                workspace,
                withdrawal,
                List.of("Подскажите, пожалуйста, с какого банка будете оплачивать?")
        );

        List<ChatMessageLogResponse> chatWithOwnQuestion = List.of(
                chatWithHello.getFirst(),
                message("bot-question", ChatMessageSenderType.BOT,
                        "Подскажите, пожалуйста, с какого банка будете оплачивать?", NOW)
        );
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(chatWithOwnQuestion);

        service.pollActiveSessions();

        verify(openAiClient, times(1)).decide(any(), any());
    }

    @Test
    void createsNewSessionForReplacementOrderAndCompletesPreviousSession() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        withdrawal.setBybitOrderId("order-new");
        AiChatSessionEntity previousSession = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        previousSession.setBybitOrderId("order-old");
        previousSession.setPayerBankConfirmed(true);
        previousSession.setPayerBankName("Т-Банк");
        previousSession.setLastProcessedMessageId("old-message");

        when(sessionRepository.findByWithdrawalRequestAndBybitOrderId(withdrawal, "order-new"))
                .thenReturn(Optional.empty());
        when(sessionRepository.findFirstByWithdrawalRequestOrderByCreatedAtDescIdDesc(withdrawal))
                .thenReturn(Optional.of(previousSession));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(List.of());
        when(openAiClient.decide(any(AiChatSessionEntity.class), any())).thenReturn(decision(
                AiChatAction.WAIT,
                List.of(),
                "",
                "Новая сессия ждёт ответ контрагента"
        ));

        service.startForOrder(workspace, withdrawal);

        ArgumentCaptor<AiChatSessionEntity> sessionCaptor = ArgumentCaptor.forClass(AiChatSessionEntity.class);
        verify(sessionRepository, times(3)).save(sessionCaptor.capture());
        AiChatSessionEntity newSession = sessionCaptor.getAllValues().stream()
                .filter(savedSession -> savedSession != previousSession)
                .findFirst()
                .orElseThrow();

        assertThat(previousSession.getBybitOrderId()).isEqualTo("order-old");
        assertThat(previousSession.getMode()).isEqualTo(AiChatAgentMode.DISABLED);
        assertThat(previousSession.getStatus()).isEqualTo(AiChatSessionStatus.COMPLETED);
        assertThat(previousSession.getCurrentStep()).isEqualTo(AiChatStep.COMPLETED);
        assertThat(newSession).isNotSameAs(previousSession);
        assertThat(newSession.getId()).isNull();
        assertThat(newSession.getBybitOrderId()).isEqualTo("order-new");
        assertThat(newSession.getMode()).isEqualTo(AiChatAgentMode.ENABLED);
        assertThat(newSession.getStatus()).isEqualTo(AiChatSessionStatus.WAITING_COUNTERPARTY);
        assertThat(newSession.getPayerBankConfirmed()).isNull();
        assertThat(newSession.getPayerBankName()).isNull();
        assertThat(newSession.getLastProcessedMessageId()).isNull();
        verify(chatService).sendAgentMessages(workspace, withdrawal, List.of("Привет"));
    }

    @Test
    void completesOldSessionWhenWithdrawalAlreadyHasReplacementOrder() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        withdrawal.setBybitOrderId("order-new");
        AiChatSessionEntity oldSession = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        oldSession.setBybitOrderId("order-old");
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(oldSession));

        service.pollActiveSessions();

        assertThat(oldSession.getMode()).isEqualTo(AiChatAgentMode.DISABLED);
        assertThat(oldSession.getStatus()).isEqualTo(AiChatSessionStatus.COMPLETED);
        assertThat(oldSession.getCurrentStep()).isEqualTo(AiChatStep.COMPLETED);
        assertThat(oldSession.getLastDecisionSummary()).isEqualTo(
                "AI chat session belongs to a previous Bybit order"
        );
        verify(chatService, never()).getMessages(any(), any());
        verify(openAiClient, never()).decide(any(), any());
    }

    @Test
    void doesNotCreateDuplicateSessionForSameOrder() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity currentSession = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        when(sessionRepository.findByWithdrawalRequestAndBybitOrderId(
                withdrawal,
                withdrawal.getBybitOrderId()
        )).thenReturn(Optional.of(currentSession));

        service.startForOrder(workspace, withdrawal);

        verify(sessionRepository, never()).findFirstByWithdrawalRequestOrderByCreatedAtDescIdDesc(withdrawal);
        verify(sessionRepository, never()).save(any());
        verify(chatService, never()).sendAgentMessages(any(), any(), any());
        verify(openAiClient, never()).decide(any(), any());
    }

    @Test
    void doesNotSendHelloWhenOperatorAlreadyStartedConversation() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        when(sessionRepository.findByWithdrawalRequestAndBybitOrderId(
                withdrawal,
                withdrawal.getBybitOrderId()
        )).thenReturn(Optional.empty());
        List<ChatMessageLogResponse> initialChat = List.of(
                message("ad", ChatMessageSenderType.USER,
                        "Принимаю платеж с любого банка! ___ Заходите только на сумму 2580 / 3780 руб.",
                        NOW.minusSeconds(20)),
                message("operator-hello", ChatMessageSenderType.USER, "Привет", NOW.minusSeconds(15)),
                message("counterparty-bank", ChatMessageSenderType.COUNTERPARTY,
                        "привет Сбербанк принимаешь лк ест чек даю чат",
                        NOW.minusSeconds(12))
        );
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(initialChat);
        when(openAiClient.decide(any(), any())).thenReturn(new AiChatDecision(
                AiChatAction.SEND_MESSAGES,
                List.of("Да, Сбербанк принимаю"),
                "",
                AiChatConfirmation.UNKNOWN,
                AiDecisionBankType.SBERBANK,
                "Сбербанк",
                AiChatConfirmation.UNKNOWN,
                AiChatConfirmation.UNKNOWN,
                AiPaymentClaim.NOT_MENTIONED,
                "",
                "Ответил на вопрос про Сбербанк"
        ));

        service.startForOrder(workspace, withdrawal);

        verify(chatService, never()).sendAgentMessages(workspace, withdrawal, List.of("Привет"));
        verify(chatService).sendAgentMessages(workspace, withdrawal, List.of("Да, Сбербанк принимаю"));
    }

    @Test
    void sendsRequisitesOnlyAfterConfirmedFactsAndMarksWarningAsSent() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        session.setPayerBankConfirmed(true);
        session.setPayerBankName("Т-Банк");
        List<ChatMessageLogResponse> chat = List.of(message(
                "counterparty-1", ChatMessageSenderType.COUNTERPARTY, "да, отправляйте", NOW.minusSeconds(1)
        ));
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(sessionRepository.findByWithdrawalRequestAndBybitOrderId(
                withdrawal,
                withdrawal.getBybitOrderId()
        )).thenReturn(Optional.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(chat);
        when(chatService.requisiteMessages(withdrawal)).thenReturn(List.of(
                "+79194600946",
                "Т-Банк, Иван В."
        ));
        when(openAiClient.decide(eq(session), any())).thenReturn(decision(
                AiChatAction.SEND_REQUISITES,
                List.of("Хорошо, тогда реквизиты:"),
                FINAL_WARNING,
                "Все обязательные условия подтверждены"
        ));

        service.pollActiveSessions();

        verify(chatService).sendAgentMessages(workspace, withdrawal, List.of(
                "Хорошо, тогда реквизиты:",
                FINAL_WARNING,
                "+79194600946",
                "Т-Банк, Иван В."
        ));
        assertThat(session.isFinalWarningSent()).isTrue();
        assertThat(session.getRequisitesSentAt()).isEqualTo(NOW);
        assertThat(withdrawal.getRequisitesSentAt()).isEqualTo(NOW);
        assertThat(session.getStatus()).isEqualTo(AiChatSessionStatus.REQUISITES_SENT);
    }

    @Test
    void rejectsEarlyRequisitesAsksModelToCorrectAndNotifiesOperator() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        List<ChatMessageLogResponse> chat = List.of(message(
                "counterparty-1", ChatMessageSenderType.COUNTERPARTY, "дп", NOW.minusSeconds(1)
        ));
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(chat);
        when(openAiClient.decide(eq(session), any()))
                .thenReturn(decision(
                        AiChatAction.SEND_REQUISITES,
                        List.of("Реквизиты:"),
                        FINAL_WARNING,
                        "Поспешил с реквизитами"
                ))
                .thenReturn(decision(
                        AiChatAction.SEND_MESSAGES,
                        List.of("Правильно понял, что вы будете оплачивать с Т-Банка?"),
                        "",
                        "Уточняю банк"
                ));

        service.pollActiveSessions();

        verify(openAiClient, times(2)).decide(eq(session), any());
        verify(chatService).sendAgentMessages(
                workspace,
                withdrawal,
                List.of("Правильно понял, что вы будете оплачивать с Т-Банка?")
        );
        assertThat(session.getRequisitesSentAt()).isNull();
        assertThat(withdrawal.isAttentionRequired()).isTrue();
        assertThat(withdrawal.getLastWarning()).contains("Backend заблокировал действие ИИ");
    }

    @Test
    void requiresStructuredBankFactBeforeAskingThirdPartyTransfer() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        withdrawal.setThirdPartyTransfer(true);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        List<ChatMessageLogResponse> chat = List.of(
                message("counterparty-bank", ChatMessageSenderType.COUNTERPARTY,
                        "привет Сбербанк принимаешь лк есть чек дам чат",
                        NOW.minusSeconds(20)),
                message("counterparty-requisites", ChatMessageSenderType.COUNTERPARTY,
                        "реки бро",
                        NOW.minusSeconds(1))
        );
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(chat);
        when(openAiClient.decide(eq(session), any()))
                .thenReturn(decision(
                        AiChatAction.SEND_MESSAGES,
                        List.of("Ещё момент: я принимаю платёж на счёт 3 лица, вас это устраивает?"),
                        "",
                        "Банк Сбербанк уже указан в переписке"
                ))
                .thenReturn(new AiChatDecision(
                        AiChatAction.SEND_MESSAGES,
                        List.of("Да, Сбербанк принимаю. Ещё момент: платёж на счёт 3 лица вас устраивает?"),
                        "",
                        AiChatConfirmation.UNKNOWN,
                        AiDecisionBankType.SBERBANK,
                        "Сбербанк",
                        AiChatConfirmation.UNKNOWN,
                        AiChatConfirmation.UNKNOWN,
                        AiPaymentClaim.NOT_MENTIONED,
                        "",
                        "Зафиксировал банк и спросил согласие на 3 лицо"
                ));

        service.pollActiveSessions();

        verify(openAiClient, times(2)).decide(eq(session), any());
        verify(chatService).sendAgentMessages(
                workspace,
                withdrawal,
                List.of("Да, Сбербанк принимаю. Ещё момент: платёж на счёт 3 лица вас устраивает?")
        );
        assertThat(session.getPayerBankConfirmed()).isTrue();
        assertThat(session.getPayerBankName()).isEqualTo("Сбербанк");
        assertThat(session.getThirdPartyTransferConfirmed()).isNull();
    }

    @Test
    void doesNotKeepConfirmationsFromRejectedModelDecision() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        withdrawal.setRequireSenderFirstParty(true);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        List<ChatMessageLogResponse> chat = List.of(message(
                "counterparty-1", ChatMessageSenderType.COUNTERPARTY, "да", NOW.minusSeconds(1)
        ));
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(chat);
        when(openAiClient.decide(eq(session), any()))
                .thenReturn(new AiChatDecision(
                        AiChatAction.SEND_MESSAGES,
                        List.of("+79194600946"),
                        "",
                        AiChatConfirmation.YES,
                        AiDecisionBankType.TBANK,
                        "Т-Банк",
                        AiChatConfirmation.UNKNOWN,
                        AiChatConfirmation.UNKNOWN,
                        AiPaymentClaim.NOT_MENTIONED,
                        "",
                        "Ошибочно вставил реквизиты"
                ))
                .thenReturn(decision(
                        AiChatAction.SEND_REQUISITES,
                        List.of("Реквизиты:"),
                        FINAL_WARNING,
                        "Повторно запросил раннюю выдачу"
                ));

        service.pollActiveSessions();

        verify(chatService, never()).sendAgentMessages(any(), any(), any());
        assertThat(session.getSenderFirstPartyConfirmed()).isNull();
        assertThat(session.getPayerBankConfirmed()).isNull();
        assertThat(session.getStatus()).isEqualTo(AiChatSessionStatus.OPERATOR_REQUIRED);
    }

    @Test
    void preparesAndSendsWholeDryRunBatchAsIs() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.DRY_RUN);
        session.setPayerBankConfirmed(true);
        List<ChatMessageLogResponse> chat = List.of(message(
                "counterparty-1", ChatMessageSenderType.COUNTERPARTY, "да", NOW.minusSeconds(1)
        ));
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(sessionRepository.findByWithdrawalRequestAndBybitOrderId(
                withdrawal,
                withdrawal.getBybitOrderId()
        )).thenReturn(Optional.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(chat);
        when(chatService.requisiteMessages(withdrawal)).thenReturn(List.of(
                "+79194600946",
                "Т-Банк, Иван В."
        ));
        when(openAiClient.decide(eq(session), any())).thenReturn(decision(
                AiChatAction.SEND_REQUISITES,
                List.of("Реквизиты:"),
                FINAL_WARNING,
                "Можно выдать реквизиты"
        ));

        service.pollActiveSessions();

        verify(chatService, never()).sendAgentMessages(any(), any(), any());
        assertThat(service.getResponse(withdrawal).suggestedMessages()).containsExactly(
                "Реквизиты:",
                FINAL_WARNING,
                "+79194600946",
                "Т-Банк, Иван В."
        );

        UserEntity operator = new UserEntity();
        when(currentUserService.currentUser()).thenReturn(operator);
        when(workspaceAccessService.getAccessibleWorkspace("workspace", operator)).thenReturn(workspace);
        when(withdrawalRepository.findByWorkspaceAndPublicId(workspace, "abcdef0"))
                .thenReturn(Optional.of(withdrawal));
        when(sessionRepository.findByWithdrawalRequestAndBybitOrderId(
                withdrawal,
                withdrawal.getBybitOrderId()
        )).thenReturn(Optional.of(session));

        service.sendSuggestion("workspace", "abcdef0");

        verify(chatService).sendAgentMessages(workspace, withdrawal, List.of(
                "Реквизиты:",
                FINAL_WARNING,
                "+79194600946",
                "Т-Банк, Иван В."
        ));
        assertThat(session.isFinalWarningSent()).isTrue();
        assertThat(session.getRequisitesSentAt()).isEqualTo(NOW);
    }

    @Test
    void asksForCancellationWhenCounterpartyCannotPayAfterRequisites() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        session.setPayerBankConfirmed(true);
        session.setRequisitesSentAt(NOW.minusSeconds(60));
        session.setFinalWarningSent(true);
        session.setStatus(AiChatSessionStatus.REQUISITES_SENT);
        session.setCurrentStep(AiChatStep.REQUISITES_SENT);
        ChatMessageLogResponse message = message(
                "counterparty-cancel",
                ChatMessageSenderType.COUNTERPARTY,
                "бро, прости, карту блокнули",
                NOW.minusSeconds(1)
        );
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(List.of(message));
        when(openAiClient.decide(eq(session), any())).thenReturn(decision(
                AiChatAction.REQUEST_CANCELLATION,
                List.of(
                        "окей, давайте тогда отменим",
                        "лучше будет, если вы сами отмените ордер, потому что я сейчас не могу кинуть апил"
                ),
                "",
                "Контрагент сообщил, что оплатить не получится"
        ));

        service.pollActiveSessions();

        verify(chatService).sendAgentMessages(workspace, withdrawal, List.of(
                "окей, давайте тогда отменим",
                "лучше будет, если вы сами отмените ордер, потому что я сейчас не могу кинуть апил"
        ));
        assertThat(session.getStatus()).isEqualTo(AiChatSessionStatus.WAITING_CANCEL);
        assertThat(session.getCurrentStep()).isEqualTo(AiChatStep.WAITING_CANCEL);
        assertThat(withdrawal.isAttentionRequired()).isTrue();
    }

    @Test
    void rejectsPaymentQuestionWhenCounterpartyClearlyRequestsCancellation() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        session.setPayerBankConfirmed(true);
        session.setRequisitesSentAt(NOW.minusSeconds(60));
        session.setFinalWarningSent(true);
        session.setStatus(AiChatSessionStatus.REQUISITES_SENT);
        session.setCurrentStep(AiChatStep.REQUISITES_SENT);
        ChatMessageLogResponse message = message(
                "counterparty-cancel",
                ChatMessageSenderType.COUNTERPARTY,
                "давай отменим",
                NOW.minusSeconds(1)
        );
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(List.of(message));
        when(openAiClient.decide(eq(session), any()))
                .thenReturn(decision(
                        AiChatAction.SEND_MESSAGES,
                        List.of("С личного счёта Т-Банка оплатить сможете?"),
                        "",
                        "Ошибочно продолжаю уточнять оплату"
                ))
                .thenReturn(decision(
                        AiChatAction.REQUEST_CANCELLATION,
                        List.of("окей, отменяем", "лучше будет, если вы отмените ордер со своей стороны"),
                        "",
                        "Исправился на сценарий отмены"
                ));

        service.pollActiveSessions();

        verify(openAiClient, times(2)).decide(eq(session), any());
        verify(chatService).sendAgentMessages(
                workspace,
                withdrawal,
                List.of("окей, отменяем", "лучше будет, если вы отмените ордер со своей стороны")
        );
        assertThat(session.getStatus()).isEqualTo(AiChatSessionStatus.WAITING_CANCEL);
    }

    @Test
    void doesNotCallModelWhenSelectedModeIsAlreadyActive() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        UserEntity operator = new UserEntity();
        when(currentUserService.currentUser()).thenReturn(operator);
        when(workspaceAccessService.getAccessibleWorkspace("workspace", operator)).thenReturn(workspace);
        when(withdrawalRepository.findByWorkspaceAndPublicId(workspace, "abcdef0"))
                .thenReturn(Optional.of(withdrawal));
        when(sessionRepository.findByWithdrawalRequestAndBybitOrderId(
                withdrawal,
                withdrawal.getBybitOrderId()
        )).thenReturn(Optional.of(session));

        service.setMode("workspace", "abcdef0", AiChatAgentMode.ENABLED);

        verify(openAiClient, never()).decide(any(), any());
        verify(chatService, never()).getMessages(any(), any());
    }

    @Test
    void doesNotSendCompletedModelReplyAfterOperatorDisablesAgent() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        ChatMessageLogResponse incoming = message(
                "counterparty-1", ChatMessageSenderType.COUNTERPARTY, "Здравствуйте", NOW.minusSeconds(1)
        );
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(List.of(incoming));
        when(openAiClient.decide(eq(session), any())).thenReturn(decision(
                AiChatAction.SEND_MESSAGES,
                List.of("Здравствуйте"),
                "",
                "Отвечаю на приветствие"
        ));
        when(jdbcTemplate.queryForObject(any(), eq(String.class), eq(100L))).thenReturn("DISABLED");

        service.pollActiveSessions();

        verify(chatService, never()).sendAgentMessages(any(), any(), any());
        assertThat(session.getMode()).isEqualTo(AiChatAgentMode.DISABLED);
    }

    @Test
    void summarizesOldMessagesAndKeepsRecentContext() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        session.setLastProcessedMessageId("m29");
        session.setLastProcessedMessageCreatedAt(NOW.minusSeconds(2));
        List<ChatMessageLogResponse> chat = new ArrayList<>();
        for (int index = 1; index <= 30; index++) {
            chat.add(message(
                    "m" + index,
                    ChatMessageSenderType.COUNTERPARTY,
                    "Сообщение " + index,
                    NOW.minusSeconds(31L - index)
            ));
        }
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(chat);
        when(openAiClient.summarize(eq(session), any())).thenReturn("Ранее контрагент обсуждал условия оплаты.");
        when(openAiClient.decide(eq(session), any())).thenReturn(decision(
                AiChatAction.WAIT,
                List.of(),
                "",
                "Ответ уже дан, ждём"
        ));

        service.pollActiveSessions();

        ArgumentCaptor<AiChatSummaryRequest> summaryCaptor = ArgumentCaptor.forClass(AiChatSummaryRequest.class);
        verify(openAiClient).summarize(eq(session), summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().messages()).hasSize(10);
        assertThat(session.getConversationSummary()).isEqualTo("Ранее контрагент обсуждал условия оплаты.");
        assertThat(session.getLastSummarizedMessageId()).isEqualTo("m10");
        assertThat(session.getSummaryUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void sendsFiveMinuteFollowUpOnlyOnceForSameCounterpartyActivity() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        session.setPayerBankConfirmed(true);
        session.setRequisitesSentAt(NOW.minus(Duration.ofMinutes(6)));
        session.setFinalWarningSent(true);
        session.setStatus(AiChatSessionStatus.REQUISITES_SENT);
        session.setCurrentStep(AiChatStep.REQUISITES_SENT);
        withdrawal.setRequisitesSentAt(session.getRequisitesSentAt());
        ChatMessageLogResponse paying = message(
                "counterparty-1",
                ChatMessageSenderType.COUNTERPARTY,
                "оплачиваю",
                NOW.minus(Duration.ofMinutes(6))
        );
        session.setLastProcessedMessageId(paying.id());
        session.setLastProcessedMessageCreatedAt(paying.createdAt());
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(List.of(paying));
        when(openAiClient.decide(eq(session), any())).thenReturn(decision(
                AiChatAction.SEND_MESSAGES,
                List.of("Как успехи с переводом?"),
                "",
                "Мягко уточняю после пяти минут тишины"
        ));

        service.pollActiveSessions();
        service.pollActiveSessions();

        verify(openAiClient, times(1)).decide(eq(session), any());
        verify(chatService, times(1)).sendAgentMessages(
                workspace,
                withdrawal,
                List.of("Как успехи с переводом?")
        );
        assertThat(session.getLastInactivityReminderAt()).isEqualTo(NOW);
    }

    @Test
    void asksAboutMissingReceiptAfterNinetySecondsOnlyOnce() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        withdrawal.setStatus(WithdrawalStatus.PAYMENT_VERIFICATION);
        withdrawal.setVerificationStartedAt(NOW.minusSeconds(91));
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        session.setAutoReceiptEnabled(true);
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(List.of());
        when(openAiClient.decide(eq(session), any())).thenReturn(decision(
                AiChatAction.SEND_MESSAGES,
                List.of("Чек пока не пришёл. Вы точно отправили перевод?"),
                "",
                "Уточняю факт оплаты после 90 секунд без чека"
        ));

        service.pollActiveSessions();
        service.pollActiveSessions();

        verify(openAiClient, times(1)).decide(eq(session), any());
        verify(chatService, times(1)).sendAgentMessages(
                workspace,
                withdrawal,
                List.of("Чек пока не пришёл. Вы точно отправили перевод?")
        );
        assertThat(session.getPaymentVerificationReminderSentAt()).isEqualTo(NOW);
    }

    @Test
    void doesNotAskWhetherPaymentWasSentWhenCounterpartyAttachedProof() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        withdrawal.setStatus(WithdrawalStatus.PAYMENT_VERIFICATION);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        session.setPayerBankConfirmed(true);
        session.setRequisitesSentAt(NOW.minusSeconds(60));
        session.setFinalWarningSent(true);
        session.setStatus(AiChatSessionStatus.REQUISITES_SENT);
        session.setCurrentStep(AiChatStep.REQUISITES_SENT);
        ChatMessageLogResponse proof = imageMessage(
                "counterparty-proof",
                ChatMessageSenderType.COUNTERPARTY,
                "receipt.png",
                NOW.minusSeconds(1)
        );
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(List.of(proof));
        when(openAiClient.decide(eq(session), any()))
                .thenReturn(decision(
                        AiChatAction.SEND_MESSAGES,
                        List.of("Подскажите, перевод уже отправили?"),
                        "",
                        "Ошибочно переспрашиваю оплату при вложении"
                ))
                .thenReturn(new AiChatDecision(
                        AiChatAction.WAIT,
                        List.of(),
                        "",
                        AiChatConfirmation.UNKNOWN,
                        AiDecisionBankType.UNKNOWN,
                        "",
                        AiChatConfirmation.UNKNOWN,
                        AiChatConfirmation.UNKNOWN,
                        AiPaymentClaim.PAYMENT_SENT,
                        "",
                        "Контрагент приложил чек, ждём проверку"
                ));

        service.pollActiveSessions();

        verify(openAiClient, times(2)).decide(eq(session), any());
        verify(chatService, never()).sendAgentMessages(any(), any(), any());
        assertThat(session.isPaymentActuallySentClaimed()).isTrue();
    }

    @Test
    void keepsRequiredTbankReceiptAutomationEnabledWhenAiSessionIsDisabled() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        withdrawal.setPayerBankType(PayerBankType.TBANK_AUTO);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.DISABLED);
        session.setAutoReceiptEnabled(false);
        when(sessionRepository.findByWithdrawalRequestAndBybitOrderId(
                withdrawal,
                withdrawal.getBybitOrderId()
        )).thenReturn(Optional.of(session));

        assertThat(service.isAutoReceiptEnabled(withdrawal)).isTrue();
    }

    @Test
    void letsModelExplainInvalidReceiptThenStopsForOperator() {
        WorkspaceEntity workspace = workspace();
        WithdrawalRequestEntity withdrawal = withdrawal(workspace);
        AiChatSessionEntity session = session(workspace, withdrawal, AiChatAgentMode.ENABLED);
        EmailReceiptCheckEntity failed = new EmailReceiptCheckEntity();
        failed.setId(77L);
        failed.setVerificationStatus(ReceiptVerificationStatus.FAILED);
        failed.setVerificationError("сумма перевода не совпадает");
        failed.setCreatedAt(NOW.minusSeconds(1));
        when(sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(session));
        when(receiptCheckRepository
                .findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
                        withdrawal.getId(), withdrawal.getBybitOrderId(), ReceiptVerificationStatus.FAILED
                ))
                .thenReturn(Optional.of(failed));
        when(receiptCheckRepository.findByWithdrawalRequest_IdOrderByCreatedAtDescIdDesc(withdrawal.getId()))
                .thenReturn(List.of(failed));
        when(chatService.getMessages(workspace, withdrawal)).thenReturn(List.of());
        when(openAiClient.decide(eq(session), any())).thenReturn(new AiChatDecision(
                AiChatAction.HANDOFF,
                List.of("Чек получили, но сумма не совпадает. Я позвал коллегу, пожалуйста, подождите."),
                "",
                AiChatConfirmation.UNKNOWN,
                AiDecisionBankType.UNKNOWN,
                "",
                AiChatConfirmation.UNKNOWN,
                AiChatConfirmation.UNKNOWN,
                AiPaymentClaim.NOT_MENTIONED,
                "Невалидный чек: неверная сумма",
                "Объяснил ошибку чека и передал оператору"
        ));

        service.pollActiveSessions();

        verify(chatService).sendAgentMessages(
                workspace,
                withdrawal,
                List.of("Чек получили, но сумма не совпадает. Я позвал коллегу, пожалуйста, подождите.")
        );
        assertThat(session.getMode()).isEqualTo(AiChatAgentMode.DISABLED);
        assertThat(session.getStatus()).isEqualTo(AiChatSessionStatus.OPERATOR_REQUIRED);
        assertThat(session.getOperatorHandoffReason()).isEqualTo("Невалидный чек: неверная сумма");
        assertThat(withdrawal.isAttentionRequired()).isTrue();
    }

    private AiChatDecision decision(
            AiChatAction action,
            List<String> messages,
            String finalWarning,
            String summary
    ) {
        return new AiChatDecision(
                action,
                messages,
                finalWarning,
                AiChatConfirmation.UNKNOWN,
                AiDecisionBankType.UNKNOWN,
                "",
                AiChatConfirmation.UNKNOWN,
                AiChatConfirmation.UNKNOWN,
                AiPaymentClaim.NOT_MENTIONED,
                "",
                summary
        );
    }

    private WorkspaceEntity workspace() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(1L);
        workspace.setPublicId("workspace");
        workspace.setReceiptEmail("receipts@example.com");
        return workspace;
    }

    private WithdrawalRequestEntity withdrawal(WorkspaceEntity workspace) {
        BankEntity bank = new BankEntity();
        bank.setTitle("Т-Банк");
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(10L);
        withdrawal.setPublicId("abcdef0");
        withdrawal.setWorkspace(workspace);
        withdrawal.setBybitOrderId("order-10");
        withdrawal.setBybitOrderAmountRub(new java.math.BigDecimal("5000"));
        withdrawal.setStatus(WithdrawalStatus.PAYMENT_IN_PROGRESS);
        withdrawal.setPayerBankType(PayerBankType.ANY_BANK);
        withdrawal.setWithdrawalMethod(WithdrawalMethod.SBP);
        withdrawal.setThirdPartyTransfer(false);
        withdrawal.setRequireSenderFirstParty(false);
        withdrawal.setRecipientPhone("+79194600946");
        withdrawal.setRecipientName("Иван В.");
        withdrawal.setRecipientBank(bank);
        return withdrawal;
    }

    private AiChatSessionEntity session(
            WorkspaceEntity workspace,
            WithdrawalRequestEntity withdrawal,
            AiChatAgentMode mode
    ) {
        AiChatSessionEntity session = new AiChatSessionEntity();
        session.setId(100L);
        session.setWorkspace(workspace);
        session.setWithdrawalRequest(withdrawal);
        session.setBybitOrderId(withdrawal.getBybitOrderId());
        session.setMode(mode);
        session.setStatus(AiChatSessionStatus.WAITING_COUNTERPARTY);
        session.setCurrentStep(AiChatStep.PAYER_BANK);
        session.setCreatedAt(NOW.minus(Duration.ofMinutes(20)));
        session.setUpdatedAt(NOW.minus(Duration.ofMinutes(20)));
        return session;
    }

    private ChatMessageLogResponse message(
            String id,
            ChatMessageSenderType senderType,
            String text,
            Instant createdAt
    ) {
        return new ChatMessageLogResponse(
                id,
                "order-10",
                id,
                senderType,
                senderType.name(),
                new ChatMessageContentResponse(ChatMessageContentType.TEXT, text, null, null),
                null,
                "SENT",
                createdAt,
                null
        );
    }

    private ChatMessageLogResponse imageMessage(
            String id,
            ChatMessageSenderType senderType,
            String fileName,
            Instant createdAt
    ) {
        return new ChatMessageLogResponse(
                id,
                "order-10",
                id,
                senderType,
                senderType.name(),
                new ChatMessageContentResponse(ChatMessageContentType.IMAGE, null, "https://example.test/" + fileName,
                        fileName),
                null,
                "SENT",
                createdAt,
                null
        );
    }
}
