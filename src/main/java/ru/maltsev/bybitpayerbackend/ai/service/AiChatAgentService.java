package ru.maltsev.bybitpayerbackend.ai.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.ai.config.AiChatAgentProperties;
import ru.maltsev.bybitpayerbackend.ai.dto.AiChatAgentResponse;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatSessionEntity;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatSessionStatus;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatStep;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionAnswer;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionBankType;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionMessageType;
import ru.maltsev.bybitpayerbackend.ai.repository.AiChatSessionRepository;
import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitChatMessage;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.service.BybitChatService;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;
import ru.maltsev.bybitpayerbackend.receipt.model.ReceiptVerificationStatus;
import ru.maltsev.bybitpayerbackend.receipt.repository.EmailReceiptCheckRepository;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalPaymentRules;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceAccessService;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;

@Service
@Slf4j
public class AiChatAgentService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final List<AiChatSessionStatus> ACTIVE_STATUSES = List.of(
            AiChatSessionStatus.WAITING_COUNTERPARTY,
            AiChatSessionStatus.REQUISITES_SENT,
            AiChatSessionStatus.WAITING_CANCEL
    );

    private final AiChatAgentProperties properties;
    private final AiChatSessionRepository sessionRepository;
    private final WithdrawalRequestRepository withdrawalRepository;
    private final EmailReceiptCheckRepository receiptCheckRepository;
    private final BybitGateway bybitGateway;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final WorkspaceSecretService workspaceSecretService;
    private final WorkspaceAccessService workspaceAccessService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final BybitChatService chatService;
    private final OpenAiChatAgentClient openAiClient;
    private final WithdrawalEventService eventService;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiChatAgentService(
            AiChatAgentProperties properties,
            AiChatSessionRepository sessionRepository,
            WithdrawalRequestRepository withdrawalRepository,
            EmailReceiptCheckRepository receiptCheckRepository,
            BybitGateway bybitGateway,
            BybitCredentialsContext bybitCredentialsContext,
            WorkspaceSecretService workspaceSecretService,
            WorkspaceAccessService workspaceAccessService,
            CurrentUserService currentUserService,
            AuditService auditService,
            BybitChatService chatService,
            OpenAiChatAgentClient openAiClient,
            WithdrawalEventService eventService,
            Clock clock
    ) {
        this.properties = properties;
        this.sessionRepository = sessionRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.receiptCheckRepository = receiptCheckRepository;
        this.bybitGateway = bybitGateway;
        this.bybitCredentialsContext = bybitCredentialsContext;
        this.workspaceSecretService = workspaceSecretService;
        this.workspaceAccessService = workspaceAccessService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.chatService = chatService;
        this.openAiClient = openAiClient;
        this.eventService = eventService;
        this.clock = clock;
    }

    @Transactional
    public void startForOrder(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        if (!properties.isEnabled()) {
            chatService.sendRequisites(workspace, withdrawal, WithdrawalPaymentRules.isAutoReleaseEnabled(
                    withdrawal.getPayerBankType(),
                    withdrawal.getWithdrawalMethod()
            ));
            return;
        }
        if (sessionRepository.findByWithdrawalRequest(withdrawal).isPresent()) {
            return;
        }

        AiChatSessionEntity session = new AiChatSessionEntity();
        session.setWorkspace(workspace);
        session.setWithdrawalRequest(withdrawal);
        session.setEnabled(!properties.isDryRunByDefault());
        session.setStatus(AiChatSessionStatus.WAITING_COUNTERPARTY);
        session.setRequiredReceiptEmail(requiredReceiptEmail(withdrawal));
        session.setCurrentStep(firstStep(withdrawal));
        session.setCreatedAt(Instant.now(clock));
        session.setUpdatedAt(session.getCreatedAt());

        if (session.isRequiredReceiptEmail() && !StringUtils.hasText(workspace.getReceiptEmail())) {
            requireOperator(session, "Для заявки нужен чек на почту, но email workspace не заполнен", false);
            sessionRepository.save(session);
            return;
        }
        if (session.isEnabled() && !openAiClient.configured()) {
            requireOperator(session, "OpenAI API key is not configured", false);
            sessionRepository.save(session);
            return;
        }

        sessionRepository.save(session);
        eventService.add(withdrawal, WithdrawalEventType.AI_CHAT_STARTED, "AI chat agent started");
        emitMessages(session, startMessages(session), "Первое сообщение агента");
    }

    @Scheduled(fixedDelayString = "${ai.chat-agent.poll-interval:5s}")
    @Transactional
    public void pollActiveSessions() {
        if (!properties.isEnabled()) {
            return;
        }
        for (AiChatSessionEntity session : sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(ACTIVE_STATUSES)) {
            processSession(session);
        }
    }

    public boolean isAutoReceiptEnabled(WithdrawalRequestEntity withdrawal) {
        boolean staticAutoRelease = WithdrawalPaymentRules.isAutoReleaseEnabled(
                withdrawal.getPayerBankType(),
                withdrawal.getWithdrawalMethod()
        );
        if (staticAutoRelease) {
            return true;
        }
        return sessionRepository.findByWithdrawalRequest(withdrawal)
                .map(AiChatSessionEntity::isAutoReceiptEnabled)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public AiChatAgentResponse getResponse(WithdrawalRequestEntity withdrawal) {
        return sessionRepository.findByWithdrawalRequest(withdrawal)
                .map(this::toResponse)
                .orElseGet(AiChatAgentResponse::absent);
    }

    @Transactional
    public AiChatAgentResponse setMode(String workspacePublicId, String withdrawalPublicId, boolean enabled) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = withdrawalRepository.findByWorkspaceAndPublicId(workspace, withdrawalPublicId)
                .orElseThrow(() -> new EntityNotFoundException("Withdrawal request not found: " + withdrawalPublicId));
        AiChatSessionEntity session = sessionRepository.findByWithdrawalRequest(withdrawal)
                .orElseThrow(() -> BusinessException.conflict("AI chat agent has not been started for this withdrawal"));
        session.setEnabled(enabled);
        touch(session);
        if (!enabled) {
            prepareSuggestion(session, nextPromptMessages(session), "ИИ выключен, подготовлена подсказка оператору");
            eventService.add(withdrawal, WithdrawalEventType.AI_CHAT_DISABLED, "AI chat agent disabled by operator", currentUser);
            auditService.add(currentUser, workspace, "AI_CHAT_DISABLED", "WITHDRAWAL", withdrawal.getPublicId(), null);
        } else {
            if (!openAiClient.configured()) {
                requireOperator(session, "OpenAI API key is not configured", false);
                sessionRepository.save(session);
                return toResponse(session);
            }
            clearSuggestion(session);
            eventService.add(withdrawal, WithdrawalEventType.AI_CHAT_STARTED, "AI chat agent enabled by operator", currentUser);
            auditService.add(currentUser, workspace, "AI_CHAT_ENABLED", "WITHDRAWAL", withdrawal.getPublicId(), null);
        }
        sessionRepository.save(session);
        return toResponse(session);
    }

    @Transactional
    public void ensureManualChatAllowed(String workspacePublicId, String withdrawalPublicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = withdrawalRepository.findByWorkspaceAndPublicId(workspace, withdrawalPublicId)
                .orElseThrow(() -> new EntityNotFoundException("Withdrawal request not found: " + withdrawalPublicId));
        sessionRepository.findByWithdrawalRequest(withdrawal)
                .filter(session -> session.isEnabled() && session.getStatus() != AiChatSessionStatus.COMPLETED)
                .ifPresent(session -> {
                    throw BusinessException.conflict("AI chat mode is enabled. Disable it before sending manual messages.");
                });
    }

    @Transactional
    public AiChatAgentResponse sendSuggestion(String workspacePublicId, String withdrawalPublicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = withdrawalRepository.findByWorkspaceAndPublicId(workspace, withdrawalPublicId)
                .orElseThrow(() -> new EntityNotFoundException("Withdrawal request not found: " + withdrawalPublicId));
        AiChatSessionEntity session = sessionRepository.findByWithdrawalRequest(withdrawal)
                .orElseThrow(() -> BusinessException.conflict("AI chat agent has not been started for this withdrawal"));
        if (session.isEnabled()) {
            throw BusinessException.conflict("Suggestions can be sent manually only when AI mode is disabled");
        }
        List<String> messages = suggestedMessages(session);
        if (messages.isEmpty()) {
            throw BusinessException.conflict("AI chat agent has no suggested messages");
        }
        boolean sent = chatService.sendAgentMessages(workspace, withdrawal, messages);
        if (!sent) {
            throw BusinessException.conflict("Failed to send suggested AI messages");
        }
        if (session.getCurrentStep() == AiChatStep.REQUISITES_SENT && withdrawal.getRequisitesSentAt() == null) {
            Instant sentAt = Instant.now(clock);
            session.setRequisitesSentAt(sentAt);
            withdrawal.setRequisitesSentAt(sentAt);
            withdrawalRepository.save(withdrawal);
            eventService.add(withdrawal, WithdrawalEventType.REQUISITES_SENT, "AI suggested requisites sent by operator", currentUser);
        }
        clearSuggestion(session);
        touch(session);
        sessionRepository.save(session);
        eventService.add(withdrawal, WithdrawalEventType.AI_CHAT_MESSAGE_SENT, "AI suggested messages sent by operator", currentUser);
        auditService.add(currentUser, workspace, "AI_CHAT_SUGGESTION_SENT", "WITHDRAWAL", withdrawal.getPublicId(), null);
        return toResponse(session);
    }

    private void processSession(AiChatSessionEntity session) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            complete(session, "Bybit order is no longer linked to withdrawal");
            return;
        }
        if (withdrawal.getStatus() == WithdrawalStatus.COMPLETED) {
            complete(session, "Withdrawal completed");
            return;
        }

        if (handleReceiptFailureIfNeeded(session)) {
            sessionRepository.save(session);
            return;
        }

        List<BybitChatMessage> messages;
        try {
            messages = bybitCredentialsContext.callWith(
                    workspaceSecretService.bybitCredentials(session.getWorkspace()),
                    () -> bybitGateway.fetchChatMessages(withdrawal.getBybitOrderId())
            );
        } catch (RuntimeException exception) {
            requireOperator(session, "Не удалось прочитать чат Bybit: " + exception.getMessage(), false);
            sessionRepository.save(session);
            return;
        }

        messages.stream()
                .filter(this::incoming)
                .filter(message -> newIncoming(session, message))
                .sorted(Comparator.comparing(
                        BybitChatMessage::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .forEach(message -> handleIncoming(session, message));

        sessionRepository.save(session);
    }

    private void handleIncoming(AiChatSessionEntity session, BybitChatMessage message) {
        if (session.getStatus() == AiChatSessionStatus.OPERATOR_REQUIRED
                || session.getStatus() == AiChatSessionStatus.COMPLETED) {
            rememberProcessed(session, message);
            return;
        }
        AiChatDecision decision = decide(session, message);
        session.setLastDecisionSummary(decision.summary());
        switch (session.getCurrentStep()) {
            case SENDER_FIRST_PARTY -> handleYesNoStep(
                    session,
                    message,
                    decision,
                    "Понял. К сожалению, одно из главных требований - отправитель должен быть 1 лицом. Мы не можем принять от вас платеж, отмените, пожалуйста, ордер.",
                    () -> {
                        session.setSenderFirstPartyConfirmed(true);
                        moveToNextStep(session);
                    }
            );
            case PAYER_BANK -> handlePayerBank(session, message, decision);
            case REQUIRED_RECEIPT_EMAIL -> handleYesNoStep(
                    session,
                    message,
                    decision,
                    "К сожалению, для этой заявки официальный чек Т-Банка на почту обязателен. Мы не можем принять платеж без такого чека, отмените, пожалуйста, ордер.",
                    () -> {
                        session.setAutoReceiptEnabled(true);
                        moveToNextStep(session);
                    }
            );
            case OPTIONAL_RECEIPT_EMAIL -> handleOptionalReceipt(session, message, decision);
            case THIRD_PARTY_TRANSFER -> handleYesNoStep(
                    session,
                    message,
                    decision,
                    "Понял. Для этой заявки оплата принимается на счет 3 лица, поэтому без согласия на это условие мы не можем принять платеж. Отмените, пожалуйста, ордер.",
                    () -> {
                        session.setThirdPartyTransferConfirmed(true);
                        moveToNextStep(session);
                    }
            );
            case FINAL_WARNING -> handleYesNoStep(
                    session,
                    message,
                    decision,
                    "Понял. Тогда не отправляйте платеж и отмените, пожалуйста, ордер.",
                    () -> {
                        session.setFinalWarningConfirmed(true);
                        sendRequisites(session);
                    }
            );
            case REQUISITES_SENT, PAYMENT_WAITING_RECEIPT -> handleAfterRequisites(session, message, decision);
            case WAITING_CANCEL -> handleCancellationReminder(session, message, decision);
            case OPERATOR_HANDOFF, COMPLETED -> {
            }
        }
        rememberProcessed(session, message);
        touch(session);
    }

    private void handleYesNoStep(
            AiChatSessionEntity session,
            BybitChatMessage message,
            AiChatDecision decision,
            String negativeMessage,
            Runnable onYes
    ) {
        if (decision.answer() == AiDecisionAnswer.YES) {
            resetUnclear(session);
            onYes.run();
            return;
        }
        if (decision.answer() == AiDecisionAnswer.NO) {
            beginCancellation(session, negativeMessage);
            return;
        }
        askAgain(session, message);
    }

    private void handlePayerBank(AiChatSessionEntity session, BybitChatMessage message, AiChatDecision decision) {
        PayerBankType payerBankType = PayerBankType.effective(session.getWithdrawalRequest().getPayerBankType());
        AiDecisionBankType bankType = effectiveBankType(decision, message.message());
        if (bankType == AiDecisionBankType.UNKNOWN && decision.answer() == AiDecisionAnswer.UNCLEAR) {
            askAgain(session, message);
            return;
        }

        session.setPayerBankName(StringUtils.hasText(decision.bankName()) ? decision.bankName() : bankTitle(bankType));
        if (payerBankType == PayerBankType.TBANK_AUTO && bankType != AiDecisionBankType.TBANK) {
            beginCancellation(session, "К сожалению, по этой заявке мы принимаем оплату только с Т-Банка. Отмените, пожалуйста, ордер.");
            return;
        }
        if (payerBankType == PayerBankType.SBERBANK && bankType != AiDecisionBankType.SBERBANK) {
            beginCancellation(session, "К сожалению, по этой заявке мы принимаем оплату только со Сбербанка. Отмените, пожалуйста, ордер.");
            return;
        }

        session.setPayerBankConfirmed(true);
        session.setOptionalReceiptEmail(payerBankType == PayerBankType.ANY_BANK && bankType == AiDecisionBankType.TBANK);
        resetUnclear(session);
        moveToNextStep(session);
    }

    private void handleOptionalReceipt(AiChatSessionEntity session, BybitChatMessage message, AiChatDecision decision) {
        if (decision.answer() == AiDecisionAnswer.YES) {
            session.setAutoReceiptEnabled(true);
            resetUnclear(session);
            moveToNextStep(session);
            return;
        }
        if (decision.answer() == AiDecisionAnswer.NO) {
            session.setAutoReceiptEnabled(false);
            resetUnclear(session);
            moveToNextStep(session);
            return;
        }
        session.setUnclearRepliesCount(session.getUnclearRepliesCount() + 1);
        if (session.getUnclearRepliesCount() >= properties.getMaxUnclearRepliesPerStep()) {
            session.setAutoReceiptEnabled(false);
            resetUnclear(session);
            moveToNextStep(session);
            return;
        }
        emitMessages(session, List.of("Не совсем понял. Сможете отправить официальный чек Т-Банка на нашу почту после перевода? Можно ответить «да» или «нет»."), "Повтор вопроса про опциональный чек");
    }

    private void handleAfterRequisites(
            AiChatSessionEntity session,
            BybitChatMessage message,
            AiChatDecision decision
    ) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        if (withdrawal.getStatus() == WithdrawalStatus.PAYMENT_VERIFICATION
                && !isAutoReceiptEnabled(withdrawal)) {
            requireOperatorWithChat(session, "Платеж ожидает ручной проверки оператором");
            return;
        }
        if (withdrawal.getStatus() == WithdrawalStatus.PAYMENT_VERIFICATION
                && (decision.messageType() == AiDecisionMessageType.PAYMENT_SENT
                || decision.messageType() == AiDecisionMessageType.RELEASE_REQUEST
                || containsAny(message.message(), "отправ", "оплат", "отпуст", "как там"))) {
            session.setPaidWithoutReceiptRepliesCount(session.getPaidWithoutReceiptRepliesCount() + 1);
            if (session.getPaidWithoutReceiptRepliesCount() > properties.getMaxPaidWithoutReceiptReplies()) {
                requireOperatorWithChat(session, "Контрагент несколько раз спрашивает об оплате, чек не подтвержден");
                return;
            }
            emitMessages(
                    session,
                    List.of("У нас работает автоматическое подтверждение заявки по официальному чеку. Если ордер до сих пор не закрылся, значит чек ещё не пришёл или ещё проверяется."),
                    "Ответ о проверке чека"
            );
            return;
        }
        if (decision.messageType() == AiDecisionMessageType.REQUISITES_CONFIRMATION
                || containsAny(message.message(), "отправляю", "перевожу", "верно", "правильно")) {
            emitMessages(session, List.of("Да, всё верно. Отправляйте ровно " + amountText(withdrawal) + " по указанным реквизитам."), "Подтверждение реквизитов");
            return;
        }
        if (decision.asksHumanOperator() || decision.unsafeOrManipulative()) {
            requireOperatorWithChat(session, "Контрагент попросил нестандартную помощь");
            return;
        }
        String reply = safeReply(decision.replyText());
        if (StringUtils.hasText(reply)) {
            emitMessages(session, List.of(reply), "Свободный ответ ИИ");
        }
    }

    private void handleCancellationReminder(
            AiChatSessionEntity session,
            BybitChatMessage message,
            AiChatDecision decision
    ) {
        if (decision.messageType() == AiDecisionMessageType.PAYMENT_SENT
                || decision.messageType() == AiDecisionMessageType.RELEASE_REQUEST) {
            requireOperatorWithChat(session, "Контрагент нажал оплату или просит отпустить ордер после отказа по условиям");
            return;
        }
        session.setCancellationRepliesCount(session.getCancellationRepliesCount() + 1);
        if (session.getCancellationRepliesCount() > properties.getMaxCancellationReplies()) {
            requireOperatorWithChat(session, "Контрагент долго не отменяет ордер после отказа по условиям");
            return;
        }
        emitMessages(
                session,
                List.of("Пожалуйста, отмените ордер со своей стороны. Мы не можем принять оплату по этой заявке. Коллегу я уже позвал, но быстрее всего будет отменить ордер самостоятельно."),
                "Напоминание об отмене ордера"
        );
    }

    private boolean handleReceiptFailureIfNeeded(AiChatSessionEntity session) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        Optional<EmailReceiptCheckEntity> failedCheck = receiptCheckRepository
                .findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
                        withdrawal.getId(),
                        withdrawal.getBybitOrderId(),
                        ReceiptVerificationStatus.FAILED
                );
        if (failedCheck.isEmpty() || failedCheck.get().getId().equals(session.getLastReceiptCheckIdHandled())) {
            return false;
        }
        EmailReceiptCheckEntity check = failedCheck.get();
        session.setLastReceiptCheckIdHandled(check.getId());
        String reason = StringUtils.hasText(check.getVerificationError())
                ? check.getVerificationError()
                : "данные чека не совпали с заявкой";
        emitMessages(
                session,
                List.of("Мы получили ваш чек, но проверка не прошла: " + reason + ". Пожалуйста, подождите, я позвал коллегу, он поможет решить этот вопрос."),
                "Невалидный чек"
        );
        requireOperator(session, "Невалидный чек: " + reason, true);
        return true;
    }

    private AiChatDecision decide(AiChatSessionEntity session, BybitChatMessage message) {
        if (!openAiClient.configured()) {
            requireOperator(session, "OpenAI API key is not configured", false);
            throw new OpenAiUnavailableException("OpenAI API key is not configured");
        }
        try {
            return openAiClient.decide(session, decisionRequest(session, message));
        } catch (OpenAiUnavailableException exception) {
            requireOperator(session, "OpenAI unavailable: " + exception.getMessage(), false);
            return AiChatDecision.unclear("OpenAI unavailable");
        }
    }

    private AiChatDecisionRequest decisionRequest(AiChatSessionEntity session, BybitChatMessage message) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        String systemPrompt = """
                Ты ИИ-помощник продавца USDT в Bybit P2P. Твоя задача - понять короткое сообщение контрагента.
                Backend сам решает, когда выдавать реквизиты, отпускать нельзя, отменять нельзя, обещать отпуск ордера нельзя.
                Можно отвечать только в рамках безопасной переписки по оплате. Если ситуация нестандартная, проси оператора.
                Верни строго структурированную классификацию и короткий безопасный replyText, если обычный ответ уместен.
                """;
        String userPrompt = """
                Текущий шаг: %s
                Статус заявки: %s
                Условия заявки:
                - требуется 1 лицо: %s
                - банк отправителя в заявке: %s
                - метод реквизитов: %s
                - перевод на 3 лицо: %s
                - сумма ордера: %s
                - получатель: %s
                - телефон: %s
                - банк получателя: %s
                - карта: %s
                - счет: %s
                - email для чека: %s
                Сообщение контрагента: %s
                """.formatted(
                session.getCurrentStep(),
                withdrawal.getStatus(),
                withdrawal.isRequireSenderFirstParty(),
                PayerBankType.effective(withdrawal.getPayerBankType()).getTitle(),
                WithdrawalMethod.effective(withdrawal.getWithdrawalMethod()).getTitle(),
                withdrawal.isThirdPartyTransfer(),
                amountText(withdrawal),
                nullToDash(withdrawal.getRecipientName()),
                nullToDash(withdrawal.getRecipientPhone()),
                withdrawal.getRecipientBank() == null ? "-" : withdrawal.getRecipientBank().getTitle(),
                nullToDash(withdrawal.getRecipientCardNumber()),
                nullToDash(withdrawal.getRecipientAccountNumber()),
                nullToDash(session.getWorkspace().getReceiptEmail()),
                message.message()
        );
        return new AiChatDecisionRequest(systemPrompt, userPrompt);
    }

    private void moveToNextStep(AiChatSessionEntity session) {
        session.setCurrentStep(nextStep(session));
        emitMessages(session, nextPromptMessages(session), "Следующий вопрос агента");
    }

    private AiChatStep firstStep(WithdrawalRequestEntity withdrawal) {
        if (withdrawal.isRequireSenderFirstParty()) {
            return AiChatStep.SENDER_FIRST_PARTY;
        }
        return AiChatStep.PAYER_BANK;
    }

    private AiChatStep nextStep(AiChatSessionEntity session) {
        return switch (session.getCurrentStep()) {
            case SENDER_FIRST_PARTY -> AiChatStep.PAYER_BANK;
            case PAYER_BANK -> {
                if (session.isRequiredReceiptEmail()) {
                    yield AiChatStep.REQUIRED_RECEIPT_EMAIL;
                }
                if (session.isOptionalReceiptEmail()) {
                    yield AiChatStep.OPTIONAL_RECEIPT_EMAIL;
                }
                yield session.getWithdrawalRequest().isThirdPartyTransfer()
                        ? AiChatStep.THIRD_PARTY_TRANSFER
                        : AiChatStep.FINAL_WARNING;
            }
            case REQUIRED_RECEIPT_EMAIL, OPTIONAL_RECEIPT_EMAIL -> session.getWithdrawalRequest().isThirdPartyTransfer()
                    ? AiChatStep.THIRD_PARTY_TRANSFER
                    : AiChatStep.FINAL_WARNING;
            case THIRD_PARTY_TRANSFER -> AiChatStep.FINAL_WARNING;
            case FINAL_WARNING -> AiChatStep.REQUISITES_SENT;
            case REQUISITES_SENT, PAYMENT_WAITING_RECEIPT, WAITING_CANCEL, OPERATOR_HANDOFF, COMPLETED -> session.getCurrentStep();
        };
    }

    private List<String> startMessages(AiChatSessionEntity session) {
        List<String> messages = new ArrayList<>();
        messages.add("Привет");
        messages.addAll(nextPromptMessages(session));
        return messages;
    }

    private List<String> nextPromptMessages(AiChatSessionEntity session) {
        return switch (session.getCurrentStep()) {
            case SENDER_FIRST_PARTY -> List.of("Вы будете отправлять оплату от 1 лица? Имя отправителя в банке должно совпадать с вашим верифицированным именем на Bybit.");
            case PAYER_BANK -> List.of(payerBankQuestion(session.getWithdrawalRequest()));
            case REQUIRED_RECEIPT_EMAIL -> List.of("Сможете отправить официальный чек Т-Банка на нашу почту после перевода?");
            case OPTIONAL_RECEIPT_EMAIL -> List.of("Если сможете отправить официальный чек Т-Банка на нашу почту, ордер проверится автоматически. Сможете отправить чек на почту?");
            case THIRD_PARTY_TRANSFER -> List.of("Хочу зафиксировать на всякий случай: я принимаю оплату на счет 3 лица. Вы согласны с этим условием?");
            case FINAL_WARNING -> List.of(finalWarning(session.getWithdrawalRequest()));
            case REQUISITES_SENT, PAYMENT_WAITING_RECEIPT, WAITING_CANCEL, OPERATOR_HANDOFF, COMPLETED -> List.of();
        };
    }

    private String payerBankQuestion(WithdrawalRequestEntity withdrawal) {
        return switch (PayerBankType.effective(withdrawal.getPayerBankType())) {
            case TBANK_AUTO -> "Вы будете отправлять оплату с Т-Банка?";
            case SBERBANK -> "Вы будете отправлять оплату со Сбербанка?";
            case ANY_BANK -> "Подскажите, пожалуйста, с какого банка будете отправлять оплату?";
        };
    }

    private String finalWarning(WithdrawalRequestEntity withdrawal) {
        List<String> constraints = new ArrayList<>();
        if (withdrawal.isRequireSenderFirstParty()) {
            constraints.add("не от 1 лица");
        }
        if (PayerBankType.effective(withdrawal.getPayerBankType()) != PayerBankType.ANY_BANK) {
            constraints.add("не с нужного банка");
        }
        constraints.add("не на те реквизиты");
        constraints.add("не той суммой");
        return "Пожалуйста, отнеситесь к переводу внимательно: отправьте ровно "
                + amountText(withdrawal)
                + ". Если перевод будет "
                + String.join(", либо ", constraints)
                + ", вы можете потерять деньги. Подтверждаете?";
    }

    private void sendRequisites(AiChatSessionEntity session) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        List<String> messages = chatService.requisiteMessages(withdrawal);
        if (session.isRequiredReceiptEmail() || session.isAutoReceiptEnabled()) {
            messages = new ArrayList<>(messages);
            messages.add(session.getWorkspace().getReceiptEmail());
        }
        boolean sendNow = session.isEnabled();
        emitMessages(session, messages, "Реквизиты отправлены после подтверждений");
        if (session.getStatus() == AiChatSessionStatus.OPERATOR_REQUIRED) {
            return;
        }
        session.setCurrentStep(AiChatStep.REQUISITES_SENT);
        session.setStatus(AiChatSessionStatus.REQUISITES_SENT);
        if (sendNow) {
            session.setRequisitesSentAt(Instant.now(clock));
            withdrawal.setRequisitesSentAt(session.getRequisitesSentAt());
            withdrawalRepository.save(withdrawal);
            eventService.add(withdrawal, WithdrawalEventType.REQUISITES_SENT, "Requisites sent by AI chat agent");
        }
    }

    private void beginCancellation(AiChatSessionEntity session, String message) {
        session.setStatus(AiChatSessionStatus.WAITING_CANCEL);
        session.setCurrentStep(AiChatStep.WAITING_CANCEL);
        emitMessages(session, List.of(
                message,
                "Сейчас я не могу отправить запрос на отмену со своей стороны, поэтому позвал коллегу. Но быстрее всего будет, если вы отмените ордер самостоятельно."
        ), "Просьба отменить ордер");
        notifyOperator(session, "Контрагент не прошёл обязательное условие");
    }

    private void askAgain(AiChatSessionEntity session, BybitChatMessage message) {
        session.setUnclearRepliesCount(session.getUnclearRepliesCount() + 1);
        if (session.getUnclearRepliesCount() >= properties.getMaxUnclearRepliesPerStep()) {
            requireOperatorWithChat(session, "Контрагент несколько раз ответил непонятно на шаг " + session.getCurrentStep());
            return;
        }
        emitMessages(
                session,
                List.of("Не совсем понял ваш ответ. Пожалуйста, ответьте прямо на вопрос: " + questionShort(session)),
                "Повтор вопроса после непонятного ответа"
        );
    }

    private String questionShort(AiChatSessionEntity session) {
        return switch (session.getCurrentStep()) {
            case SENDER_FIRST_PARTY -> "вы будете отправлять от 1 лица?";
            case PAYER_BANK -> payerBankQuestion(session.getWithdrawalRequest());
            case REQUIRED_RECEIPT_EMAIL, OPTIONAL_RECEIPT_EMAIL -> "сможете отправить официальный чек на почту?";
            case THIRD_PARTY_TRANSFER -> "вы согласны на оплату на счет 3 лица?";
            case FINAL_WARNING -> "вы подтверждаете условия перевода?";
            default -> "подтвердите, пожалуйста";
        };
    }

    private void requireOperatorWithChat(AiChatSessionEntity session, String reason) {
        emitMessages(session, List.of("Пожалуйста, подождите, я позвал коллегу. Он поможет решить этот вопрос."), "Передача оператору");
        requireOperator(session, reason, true);
    }

    private void requireOperator(AiChatSessionEntity session, String reason, boolean keepConversationContext) {
        session.setEnabled(false);
        session.setStatus(AiChatSessionStatus.OPERATOR_REQUIRED);
        session.setCurrentStep(AiChatStep.OPERATOR_HANDOFF);
        session.setOperatorRequiredAt(Instant.now(clock));
        session.setLastDecisionSummary(reason);
        session.getWithdrawalRequest().setAttentionRequired(true);
        session.getWithdrawalRequest().setLastWarning(reason);
        withdrawalRepository.save(session.getWithdrawalRequest());
        eventService.add(session.getWithdrawalRequest(), WithdrawalEventType.AI_CHAT_OPERATOR_REQUIRED, reason);
        if (!keepConversationContext) {
            clearSuggestion(session);
        }
        touch(session);
    }

    private void notifyOperator(AiChatSessionEntity session, String reason) {
        session.getWithdrawalRequest().setAttentionRequired(true);
        session.getWithdrawalRequest().setLastWarning(reason);
        withdrawalRepository.save(session.getWithdrawalRequest());
        eventService.add(session.getWithdrawalRequest(), WithdrawalEventType.AI_CHAT_OPERATOR_REQUIRED, reason);
        touch(session);
    }

    private void complete(AiChatSessionEntity session, String reason) {
        session.setEnabled(false);
        session.setStatus(AiChatSessionStatus.COMPLETED);
        session.setCurrentStep(AiChatStep.COMPLETED);
        session.setLastDecisionSummary(reason);
        clearSuggestion(session);
        touch(session);
        sessionRepository.save(session);
    }

    private void emitMessages(AiChatSessionEntity session, List<String> messages, String reason) {
        List<String> sanitized = messages.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (sanitized.isEmpty()) {
            clearSuggestion(session);
            return;
        }
        if (!session.isEnabled()) {
            prepareSuggestion(session, sanitized, reason);
            return;
        }
        boolean sent = chatService.sendAgentMessages(session.getWorkspace(), session.getWithdrawalRequest(), sanitized);
        if (!sent) {
            requireOperator(session, "Не удалось отправить сообщение ИИ в чат Bybit", false);
            return;
        }
        clearSuggestion(session);
        eventService.add(session.getWithdrawalRequest(), WithdrawalEventType.AI_CHAT_MESSAGE_SENT, reason);
    }

    private void prepareSuggestion(AiChatSessionEntity session, List<String> messages, String reason) {
        try {
            session.setSuggestedMessagesJson(objectMapper.writeValueAsString(messages));
            session.setSuggestedReason(reason);
            session.setSuggestedAt(Instant.now(clock));
            eventService.add(session.getWithdrawalRequest(), WithdrawalEventType.AI_CHAT_SUGGESTION_CREATED, reason);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize AI chat suggestion", exception);
        }
    }

    private void clearSuggestion(AiChatSessionEntity session) {
        session.setSuggestedMessagesJson(null);
        session.setSuggestedReason(null);
        session.setSuggestedAt(null);
    }

    private List<String> suggestedMessages(AiChatSessionEntity session) {
        if (!StringUtils.hasText(session.getSuggestedMessagesJson())) {
            return List.of();
        }
        try {
            return objectMapper.readValue(session.getSuggestedMessagesJson(), STRING_LIST_TYPE);
        } catch (IOException exception) {
            return List.of();
        }
    }

    private AiChatAgentResponse toResponse(AiChatSessionEntity session) {
        return new AiChatAgentResponse(
                true,
                session.isEnabled(),
                session.getStatus().name(),
                session.getStatus().getTitle(),
                session.getCurrentStep().name(),
                session.getCurrentStep().getTitle(),
                session.isAutoReceiptEnabled(),
                session.getStatus() == AiChatSessionStatus.OPERATOR_REQUIRED,
                suggestedMessages(session),
                session.getSuggestedReason(),
                session.getSuggestedAt(),
                session.getLastDecisionSummary()
        );
    }

    private boolean incoming(BybitChatMessage message) {
        return !message.system() && "user".equalsIgnoreCase(message.roleType());
    }

    private boolean newIncoming(AiChatSessionEntity session, BybitChatMessage message) {
        if (message.createdAt() != null && message.createdAt().isBefore(session.getCreatedAt())) {
            return false;
        }
        Instant lastCreatedAt = session.getLastProcessedMessageCreatedAt();
        if (lastCreatedAt == null) {
            return true;
        }
        return message.createdAt() == null || message.createdAt().isAfter(lastCreatedAt);
    }

    private void rememberProcessed(AiChatSessionEntity session, BybitChatMessage message) {
        session.setLastProcessedMessageId(message.id());
        session.setLastProcessedMessageCreatedAt(message.createdAt());
    }

    private void touch(AiChatSessionEntity session) {
        session.setUpdatedAt(Instant.now(clock));
    }

    private void resetUnclear(AiChatSessionEntity session) {
        session.setUnclearRepliesCount(0);
    }

    private boolean requiredReceiptEmail(WithdrawalRequestEntity withdrawal) {
        return WithdrawalPaymentRules.isAutoReleaseEnabled(
                withdrawal.getPayerBankType(),
                withdrawal.getWithdrawalMethod()
        );
    }

    private AiDecisionBankType effectiveBankType(AiChatDecision decision, String rawMessage) {
        if (decision.bankType() != AiDecisionBankType.UNKNOWN) {
            return decision.bankType();
        }
        String text = rawMessage.toLowerCase(Locale.ROOT);
        if (containsAny(text, "т-банк", "тинькофф", "tinkoff", "t-bank", "tbank")) {
            return AiDecisionBankType.TBANK;
        }
        if (containsAny(text, "сбер", "sber")) {
            return AiDecisionBankType.SBERBANK;
        }
        return AiDecisionBankType.UNKNOWN;
    }

    private boolean containsAny(String source, String... fragments) {
        String normalized = source == null ? "" : source.toLowerCase(Locale.ROOT);
        for (String fragment : fragments) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private String safeReply(String replyText) {
        if (!StringUtils.hasText(replyText)) {
            return "";
        }
        String trimmed = replyText.trim();
        if (trimmed.length() > 500) {
            return trimmed.substring(0, 500);
        }
        return trimmed;
    }

    private String bankTitle(AiDecisionBankType bankType) {
        return switch (bankType) {
            case TBANK -> "Т-Банк";
            case SBERBANK -> "Сбербанк";
            case OTHER -> "Другой банк";
            case UNKNOWN -> "";
        };
    }

    private String amountText(WithdrawalRequestEntity withdrawal) {
        BigDecimal amount = withdrawal.getBybitOrderAmountRub() == null
                ? withdrawal.getAmountRub()
                : withdrawal.getBybitOrderAmountRub();
        if (amount == null) {
            return "сумму ордера";
        }
        return amount.stripTrailingZeros().toPlainString() + " RUB";
    }

    private String nullToDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }
}
