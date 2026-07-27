package ru.maltsev.bybitpayerbackend.ai.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.ai.config.AiChatAgentProperties;
import ru.maltsev.bybitpayerbackend.ai.dto.AiChatAgentResponse;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatSessionEntity;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatAction;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatAgentMode;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatConfirmation;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatSessionStatus;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatStep;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatTrigger;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionBankType;
import ru.maltsev.bybitpayerbackend.ai.model.AiPaymentClaim;
import ru.maltsev.bybitpayerbackend.ai.repository.AiChatSessionRepository;
import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageContentResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageContentType;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageSenderType;
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

@Service
@Slf4j
public class AiChatAgentService {

    private static final String HELLO_MESSAGE = "Привет";
    private static final List<String> COUNTERPARTY_CANCEL_PHRASES = List.of(
            "давай отмен",
            "давайте отмен",
            "отменим",
            "отменить ордер",
            "отмените ордер",
            "отменяй",
            "не могу оплат",
            "не смогу оплат",
            "не получается оплат",
            "не получится оплат",
            "не выйдет оплат",
            "оплаты не будет",
            "карта блок",
            "карту блок",
            "банк заблок",
            "чел слился",
            "чел слил",
            "чел мороз",
            "человек слился",
            "человек не отвечает"
    );
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
    private final WorkspaceAccessService workspaceAccessService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final BybitChatService chatService;
    private final OpenAiChatAgentClient openAiClient;
    private final AiChatPromptProvider promptProvider;
    private final WithdrawalEventService eventService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiChatAgentService(
            AiChatAgentProperties properties,
            AiChatSessionRepository sessionRepository,
            WithdrawalRequestRepository withdrawalRepository,
            EmailReceiptCheckRepository receiptCheckRepository,
            WorkspaceAccessService workspaceAccessService,
            CurrentUserService currentUserService,
            AuditService auditService,
            BybitChatService chatService,
            OpenAiChatAgentClient openAiClient,
            AiChatPromptProvider promptProvider,
            WithdrawalEventService eventService,
            JdbcTemplate jdbcTemplate,
            Clock clock
    ) {
        this.properties = properties;
        this.sessionRepository = sessionRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.receiptCheckRepository = receiptCheckRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.chatService = chatService;
        this.openAiClient = openAiClient;
        this.promptProvider = promptProvider;
        this.eventService = eventService;
        this.jdbcTemplate = jdbcTemplate;
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

        Optional<AiChatSessionEntity> existingSession = sessionRepository.findByWithdrawalRequest(withdrawal);
        if (existingSession
                .filter(session -> StringUtils.hasText(session.getBybitOrderId()))
                .filter(session -> Objects.equals(session.getBybitOrderId(), withdrawal.getBybitOrderId()))
                .isPresent()) {
            return;
        }

        boolean restarted = existingSession.isPresent();
        AiChatSessionEntity session = existingSession.orElseGet(AiChatSessionEntity::new);
        resetForOrder(session, workspace, withdrawal);

        if (session.isRequiredReceiptEmail() && !StringUtils.hasText(workspace.getReceiptEmail())) {
            requireOperator(session, "Для заявки обязателен чек на почту, но email workspace не заполнен");
            sessionRepository.save(session);
            return;
        }
        if (!openAiClient.configured()) {
            requireOperator(session, "OpenAI API key is not configured");
            sessionRepository.save(session);
            return;
        }

        sessionRepository.save(session);
        eventService.add(
                withdrawal,
                WithdrawalEventType.AI_CHAT_STARTED,
                restarted ? "AI chat agent restarted for new Bybit order" : "AI chat agent started"
        );

        List<ChatMessageLogResponse> messages = readChatOrHandoff(session);
        if (messages == null) {
            sessionRepository.save(session);
            return;
        }

        if (!operatorAlreadyStartedConversation(messages)) {
            if (!sendNow(session, List.of(HELLO_MESSAGE))) {
                sessionRepository.save(session);
                return;
            }
            messages = readChatOrHandoff(session);
            if (messages == null) {
                sessionRepository.save(session);
                return;
            }
        }
        List<ChatMessageLogResponse> counterpartMessages = counterpartMessages(messages);
        runDecision(
                session,
                AiChatTrigger.START,
                "Ордер найден, агент начал диалог",
                messages,
                messageIds(counterpartMessages)
        );
        rememberProcessed(session, lastMessage(counterpartMessages));
        sessionRepository.save(session);
    }

    private void resetForOrder(
            AiChatSessionEntity session,
            WorkspaceEntity workspace,
            WithdrawalRequestEntity withdrawal
    ) {
        Instant now = Instant.now(clock);
        session.setWorkspace(workspace);
        session.setWithdrawalRequest(withdrawal);
        session.setBybitOrderId(withdrawal.getBybitOrderId());
        session.setMode(AiChatAgentMode.ENABLED);
        session.setStatus(AiChatSessionStatus.WAITING_COUNTERPARTY);
        session.setCurrentStep(initialStep(withdrawal));
        session.setAutoReceiptEnabled(WithdrawalPaymentRules.isAutoReleaseEnabled(
                withdrawal.getPayerBankType(),
                withdrawal.getWithdrawalMethod()
        ));
        session.setRequiredReceiptEmail(requiredReceiptEmail(withdrawal));
        session.setOptionalReceiptEmail(false);
        session.setSenderFirstPartyConfirmed(null);
        session.setPayerBankConfirmed(null);
        session.setPayerBankName(null);
        session.setReceiptEmailConfirmed(null);
        session.setThirdPartyTransferConfirmed(null);
        session.setFinalWarningSent(false);
        session.setPaymentActuallySentClaimed(false);
        session.setRequisitesSentAt(null);
        session.setOperatorRequiredAt(null);
        session.setOperatorHandoffReason(null);
        session.setLastProcessedMessageId(null);
        session.setLastProcessedMessageCreatedAt(null);
        session.setLastReceiptCheckIdHandled(null);
        session.setLastInactivityReminderAt(null);
        session.setPaymentVerificationReminderSentAt(null);
        session.setLastDecisionSummary(null);
        session.setLastAction(null);
        session.setConversationSummary(null);
        session.setSummaryUpdatedAt(null);
        session.setLastSummarizedMessageId(null);
        clearSuggestion(session);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
    }

    @Scheduled(fixedDelayString = "${ai.chat-agent.poll-interval:5s}")
    @Transactional
    public void pollActiveSessions() {
        if (!properties.isEnabled()) {
            return;
        }
        for (AiChatSessionEntity session : sessionRepository.findByStatusInOrderByUpdatedAtAscIdAsc(ACTIVE_STATUSES)) {
            try {
                processSession(session);
            } catch (RuntimeException exception) {
                log.error("AI chat session polling failed: sessionId={}", session.getId(), exception);
                requireOperator(session, "Ошибка обработки ИИ-чата: " + safeError(exception));
                sessionRepository.save(session);
            }
        }
    }

    public boolean isAutoReceiptEnabled(WithdrawalRequestEntity withdrawal) {
        boolean staticAutoRelease = WithdrawalPaymentRules.isAutoReleaseEnabled(
                withdrawal.getPayerBankType(),
                withdrawal.getWithdrawalMethod()
        );
        return staticAutoRelease || sessionRepository.findByWithdrawalRequest(withdrawal)
                .map(AiChatSessionEntity::isAutoReceiptEnabled)
                .orElse(staticAutoRelease);
    }

    @Transactional(readOnly = true)
    public AiChatAgentResponse getResponse(WithdrawalRequestEntity withdrawal) {
        return sessionRepository.findByWithdrawalRequest(withdrawal)
                .map(this::toResponse)
                .orElseGet(AiChatAgentResponse::absent);
    }

    @Transactional
    public AiChatAgentResponse setMode(
            String workspacePublicId,
            String withdrawalPublicId,
            AiChatAgentMode mode
    ) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = withdrawalRepository.findByWorkspaceAndPublicId(workspace, withdrawalPublicId)
                .orElseThrow(() -> new EntityNotFoundException("Withdrawal request not found: " + withdrawalPublicId));
        AiChatSessionEntity session = sessionRepository.findByWithdrawalRequest(withdrawal)
                .orElseThrow(() -> BusinessException.conflict("AI chat agent has not been started for this withdrawal"));

        if (session.getMode() == mode) {
            return toResponse(session);
        }
        if (session.getStatus() == AiChatSessionStatus.COMPLETED) {
            throw BusinessException.conflict("AI chat session is already completed");
        }
        if (mode != AiChatAgentMode.DISABLED && !openAiClient.configured()) {
            requireOperator(session, "OpenAI API key is not configured");
            sessionRepository.save(session);
            return toResponse(session);
        }

        session.setMode(mode);
        clearSuggestion(session);
        if (session.getStatus() == AiChatSessionStatus.OPERATOR_REQUIRED && mode != AiChatAgentMode.DISABLED) {
            session.setStatus(requisitesSent(session)
                    ? AiChatSessionStatus.REQUISITES_SENT
                    : AiChatSessionStatus.WAITING_COUNTERPARTY);
            session.setOperatorRequiredAt(null);
            session.setOperatorHandoffReason(null);
            session.setCurrentStep(nextRequiredStep(session));
        }
        touch(session);

        String auditAction = switch (mode) {
            case ENABLED -> "AI_CHAT_ENABLED";
            case DISABLED -> "AI_CHAT_DISABLED";
            case DRY_RUN -> "AI_CHAT_DRY_RUN";
        };
        auditService.add(currentUser, workspace, auditAction, "WITHDRAWAL", withdrawal.getPublicId(), null);
        eventService.add(
                withdrawal,
                WithdrawalEventType.AI_CHAT_MODE_CHANGED,
                "AI chat mode changed to " + mode,
                currentUser
        );

        if (mode != AiChatAgentMode.DISABLED) {
            List<ChatMessageLogResponse> messages = readChatOrHandoff(session);
            if (messages != null) {
                runDecision(
                        session,
                        AiChatTrigger.MODE_CHANGED,
                        "Оператор переключил режим на " + mode,
                        messages,
                        Set.of()
                );
            }
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
                .filter(session -> session.getMode() == AiChatAgentMode.ENABLED)
                .filter(session -> session.getStatus() != AiChatSessionStatus.COMPLETED)
                .ifPresent(session -> {
                    throw BusinessException.conflict(
                            "AI chat mode is enabled. Select DISABLED or DRY_RUN before sending manual messages."
                    );
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
        if (session.getMode() != AiChatAgentMode.DRY_RUN) {
            throw BusinessException.conflict("AI suggestions can be sent only in DRY_RUN mode");
        }

        List<String> messages = suggestedMessages(session);
        AiChatAction action = session.getSuggestedAction();
        if (messages.isEmpty() || action == null) {
            throw BusinessException.conflict("AI chat agent has no suggested messages");
        }
        if (!chatService.sendAgentMessages(workspace, withdrawal, messages)) {
            throw BusinessException.conflict("Failed to send suggested AI messages");
        }

        applySentAction(session, action, session.getSuggestedFinalWarning());
        clearSuggestion(session);
        touch(session);
        sessionRepository.save(session);
        auditService.add(currentUser, workspace, "AI_CHAT_SUGGESTION_SENT", "WITHDRAWAL", withdrawal.getPublicId(), null);
        return toResponse(session);
    }

    private void processSession(AiChatSessionEntity session) {
        if (session.getMode() == AiChatAgentMode.DISABLED
                || session.getStatus() == AiChatSessionStatus.OPERATOR_REQUIRED
                || session.getStatus() == AiChatSessionStatus.COMPLETED) {
            return;
        }

        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            complete(session, "Bybit order is no longer linked to withdrawal");
            return;
        }
        if (withdrawal.getStatus() == WithdrawalStatus.COMPLETED) {
            complete(session, "Withdrawal completed");
            return;
        }

        Optional<EmailReceiptCheckEntity> failedReceipt = newFailedReceipt(session);
        if (failedReceipt.isPresent()) {
            EmailReceiptCheckEntity receipt = failedReceipt.get();
            session.setLastReceiptCheckIdHandled(receipt.getId());
            List<ChatMessageLogResponse> messages = readChatOrHandoff(session);
            if (messages != null) {
                String error = StringUtils.hasText(receipt.getVerificationError())
                        ? receipt.getVerificationError()
                        : "данные чека не совпали с заявкой";
                runDecision(
                        session,
                        AiChatTrigger.INVALID_RECEIPT,
                        "Получен невалидный чек. Причина backend: " + error,
                        messages,
                        Set.of()
                );
            }
            sessionRepository.save(session);
            return;
        }

        List<ChatMessageLogResponse> messages = readChatOrHandoff(session);
        if (messages == null) {
            sessionRepository.save(session);
            return;
        }

        List<ChatMessageLogResponse> newMessages = newCounterpartyMessages(session, messages);
        if (!newMessages.isEmpty()) {
            runDecision(
                    session,
                    AiChatTrigger.NEW_COUNTERPARTY_MESSAGES,
                    "Получена новая пачка сообщений контрагента: " + newMessages.size(),
                    messages,
                    messageIds(newMessages)
            );
            rememberProcessed(session, lastMessage(newMessages));
            sessionRepository.save(session);
            return;
        }

        if (paymentVerificationReminderDue(session)) {
            session.setPaymentVerificationReminderSentAt(Instant.now(clock));
            runDecision(
                    session,
                    AiChatTrigger.PAYMENT_VERIFICATION_WITHOUT_RECEIPT,
                    "Ордер в PAYMENT_VERIFICATION не меньше 90 секунд, валидный чек не найден",
                    messages,
                    Set.of()
            );
            sessionRepository.save(session);
            return;
        }

        if (inactivityReminderDue(session, messages)) {
            session.setLastInactivityReminderAt(Instant.now(clock));
            runDecision(
                    session,
                    AiChatTrigger.INACTIVITY_AFTER_REQUISITES,
                    "После выдачи реквизитов и последней активности контрагента прошло не меньше 5 минут",
                    messages,
                    Set.of()
            );
            sessionRepository.save(session);
        }
    }

    private void runDecision(
            AiChatSessionEntity session,
            AiChatTrigger trigger,
            String triggerDetails,
            List<ChatMessageLogResponse> allMessages,
            Set<String> newMessageIds
    ) {
        if (session.getMode() == AiChatAgentMode.DISABLED) {
            return;
        }

        try {
            if (counterpartyPaymentProofAttached(session, allMessages, newMessageIds)) {
                session.setPaymentActuallySentClaimed(true);
            }
            AiChatDecisionRequest request = decisionRequest(
                    session,
                    trigger,
                    triggerDetails,
                    allMessages,
                    newMessageIds
            );
            ObservedFactsSnapshot factsBeforeDecision = observedFactsSnapshot(session);
            AiChatDecision decision = polishDecision(
                    trigger,
                    normalizeDecision(openAiClient.decide(session, request))
            );
            applyObservedFacts(session, decision);
            session.setCurrentStep(nextRequiredStep(session));

            Optional<String> validationError = validateDecision(session, trigger, decision, allMessages, newMessageIds);
            if (validationError.isPresent()) {
                String firstError = validationError.get();
                restoreObservedFacts(session, factsBeforeDecision);
                session.setCurrentStep(nextRequiredStep(session));
                notifyOperator(session, "Backend заблокировал действие ИИ: " + firstError);
                AiChatDecision corrected = polishDecision(
                        trigger,
                        normalizeDecision(openAiClient.decide(session, request.withValidationError(firstError)))
                );
                applyObservedFacts(session, corrected);
                session.setCurrentStep(nextRequiredStep(session));
                Optional<String> correctedError = validateDecision(
                        session,
                        trigger,
                        corrected,
                        allMessages,
                        newMessageIds
                );
                if (correctedError.isPresent()) {
                    restoreObservedFacts(session, factsBeforeDecision);
                    session.setCurrentStep(nextRequiredStep(session));
                    requireOperator(
                            session,
                            "ИИ дважды вернул запрещённое действие: " + correctedError.get()
                    );
                    return;
                }
                decision = corrected;
            }

            session.setLastAction(decision.action());
            session.setLastDecisionSummary(decision.summary());
            if (decision.action() == AiChatAction.HANDOFF) {
                session.setOperatorHandoffReason(decision.handoffReason());
            }
            executeDecision(session, decision);
            touch(session);
        } catch (OpenAiUnavailableException exception) {
            requireOperator(session, "OpenAI unavailable: " + exception.getMessage());
        }
    }

    private AiChatDecisionRequest decisionRequest(
            AiChatSessionEntity session,
            AiChatTrigger trigger,
            String triggerDetails,
            List<ChatMessageLogResponse> allMessages,
            Set<String> newMessageIds
    ) {
        ConversationWindow window = conversationWindow(session, allMessages);
        List<AiChatPromptMessage> input = new ArrayList<>();
        input.add(new AiChatPromptMessage(
                "user",
                "<application_context>\n" + writeJson(applicationContext(session, trigger, triggerDetails))
                        + "\n</application_context>"
        ));
        if (StringUtils.hasText(session.getConversationSummary())) {
            input.add(new AiChatPromptMessage(
                    "user",
                    "<conversation_summary>\n" + session.getConversationSummary() + "\n</conversation_summary>"
            ));
        }
        window.messages().stream()
                .map(message -> toPromptMessage(message, newMessageIds.contains(message.id())))
                .forEach(input::add);
        input.add(new AiChatPromptMessage(
                "user",
                "<decision_trigger>\nТип: " + trigger + "\nПричина: " + triggerDetails
                        + "\nВыбери одно безопасное действие с учётом всей переписки.\n</decision_trigger>"
        ));
        return new AiChatDecisionRequest(promptProvider.systemPrompt(), List.copyOf(input));
    }

    private ConversationWindow conversationWindow(
            AiChatSessionEntity session,
            List<ChatMessageLogResponse> allMessages
    ) {
        List<ChatMessageLogResponse> unsummarized = unsummarizedMessages(session, allMessages);
        int maxContextMessages = Math.max(1, properties.getMaxContextMessages());
        if (unsummarized.size() <= maxContextMessages) {
            return new ConversationWindow(unsummarized);
        }

        int retainCount = Math.min(
                Math.max(1, properties.getRetainedContextMessages()),
                maxContextMessages
        );
        int summarizeCount = unsummarized.size() - retainCount;
        List<ChatMessageLogResponse> toSummarize = List.copyOf(unsummarized.subList(0, summarizeCount));
        List<AiChatPromptMessage> summaryMessages = toSummarize.stream()
                .map(message -> toPromptMessage(message, false))
                .toList();
        String summary = openAiClient.summarize(
                session,
                new AiChatSummaryRequest(session.getConversationSummary(), summaryMessages)
        );
        session.setConversationSummary(summary);
        session.setSummaryUpdatedAt(Instant.now(clock));
        session.setLastSummarizedMessageId(toSummarize.getLast().id());
        return new ConversationWindow(List.copyOf(unsummarized.subList(summarizeCount, unsummarized.size())));
    }

    private List<ChatMessageLogResponse> unsummarizedMessages(
            AiChatSessionEntity session,
            List<ChatMessageLogResponse> allMessages
    ) {
        if (!StringUtils.hasText(session.getLastSummarizedMessageId())) {
            return allMessages;
        }
        for (int index = 0; index < allMessages.size(); index++) {
            if (Objects.equals(allMessages.get(index).id(), session.getLastSummarizedMessageId())) {
                return List.copyOf(allMessages.subList(index + 1, allMessages.size()));
            }
        }
        int retainCount = Math.min(
                Math.max(1, properties.getRetainedContextMessages()),
                allMessages.size()
        );
        return List.copyOf(allMessages.subList(allMessages.size() - retainCount, allMessages.size()));
    }

    private Map<String, Object> applicationContext(
            AiChatSessionEntity session,
            AiChatTrigger trigger,
            String triggerDetails
    ) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("trigger", trigger.name());
        context.put("triggerDetails", triggerDetails);
        context.put("agentMode", session.getMode().name());
        context.put("agentStatus", session.getStatus().name());
        context.put("currentRequirement", session.getCurrentStep().name());
        context.put("withdrawalStatus", withdrawal.getStatus().name());
        context.put("orderAmount", amountText(withdrawal));
        context.put("requireSenderFirstParty", withdrawal.isRequireSenderFirstParty());
        context.put("payerBankRequirement", PayerBankType.effective(withdrawal.getPayerBankType()).name());
        context.put("withdrawalMethod", WithdrawalMethod.effective(withdrawal.getWithdrawalMethod()).name());
        context.put("thirdPartyTransfer", withdrawal.isThirdPartyTransfer());
        context.put("receiptEmailRequired", session.isRequiredReceiptEmail());
        context.put("receiptEmailOptional", session.isOptionalReceiptEmail());
        context.put("autoReceiptEnabled", session.isAutoReceiptEnabled());
        context.put("receiptEmail", nullToEmpty(session.getWorkspace().getReceiptEmail()));
        context.put("requisitesSent", requisitesSent(session));
        context.put("requisitesSentAt", instantText(session.getRequisitesSentAt()));
        context.put("confirmations", confirmationContext(session));
        context.put("requisites", requisiteContext(withdrawal));
        context.put("latestReceipt", receiptContext(withdrawal));
        context.put("operatorHandoffReason", nullToEmpty(session.getOperatorHandoffReason()));
        return context;
    }

    private Map<String, Object> confirmationContext(AiChatSessionEntity session) {
        Map<String, Object> confirmations = new LinkedHashMap<>();
        confirmations.put("firstPartyConfirmed", session.getSenderFirstPartyConfirmed());
        confirmations.put("payerBankConfirmed", session.getPayerBankConfirmed());
        confirmations.put("payerBankName", nullToEmpty(session.getPayerBankName()));
        confirmations.put("receiptEmailConfirmed", session.getReceiptEmailConfirmed());
        confirmations.put("thirdPartyTransferConfirmed", session.getThirdPartyTransferConfirmed());
        confirmations.put("finalWarningSent", session.isFinalWarningSent());
        confirmations.put("paymentActuallySentClaimed", session.isPaymentActuallySentClaimed());
        return confirmations;
    }

    private Map<String, Object> requisiteContext(WithdrawalRequestEntity withdrawal) {
        Map<String, Object> requisites = new LinkedHashMap<>();
        requisites.put("recipientName", nullToEmpty(withdrawal.getRecipientName()));
        requisites.put("recipientBank", withdrawal.getRecipientBank() == null
                ? ""
                : withdrawal.getRecipientBank().getTitle());
        requisites.put("recipientPhone", nullToEmpty(withdrawal.getRecipientPhone()));
        requisites.put("recipientCardNumber", nullToEmpty(withdrawal.getRecipientCardNumber()));
        requisites.put("recipientAccountNumber", nullToEmpty(withdrawal.getRecipientAccountNumber()));
        return requisites;
    }

    private Map<String, Object> receiptContext(WithdrawalRequestEntity withdrawal) {
        List<EmailReceiptCheckEntity> checks = receiptCheckRepository
                .findByWithdrawalRequest_IdOrderByCreatedAtDescIdDesc(withdrawal.getId());
        if (checks.isEmpty()) {
            return Map.of("status", "NOT_FOUND");
        }
        EmailReceiptCheckEntity check = checks.getFirst();
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("status", check.getVerificationStatus().name());
        receipt.put("error", nullToEmpty(check.getVerificationError()));
        receipt.put("amount", check.getParsedAmountRub());
        receipt.put("recipientName", nullToEmpty(check.getParsedRecipientName()));
        receipt.put("recipientBank", nullToEmpty(check.getParsedRecipientBank()));
        receipt.put("createdAt", instantText(check.getCreatedAt()));
        return receipt;
    }

    private AiChatPromptMessage toPromptMessage(ChatMessageLogResponse message, boolean fresh) {
        String sender = switch (message.senderType()) {
            case COUNTERPARTY -> "counterparty";
            case BOT -> "ai_agent";
            case USER -> "operator";
            case SUPPORT -> "bybit_support";
            case SYSTEM -> "bybit_system";
        };
        String role = message.senderType() == ChatMessageSenderType.BOT
                || message.senderType() == ChatMessageSenderType.USER
                ? "assistant"
                : "user";
        String marker = fresh ? "[NEW]" : "";
        String timestamp = message.createdAt() == null ? "" : "[" + message.createdAt() + "]";
        return new AiChatPromptMessage(
                role,
                marker + "[" + sender + "]" + timestamp + " " + messageText(message)
        );
    }

    private String messageText(ChatMessageLogResponse message) {
        if (message.content() == null) {
            return "";
        }
        if (message.content().type() == ChatMessageContentType.TEXT) {
            return nullToEmpty(message.content().text());
        }
        String fileName = StringUtils.hasText(message.content().fileName())
                ? ": " + message.content().fileName()
                : "";
        return "[вложение " + message.content().type() + fileName + "]";
    }

    private void applyObservedFacts(AiChatSessionEntity session, AiChatDecision decision) {
        if (decision.firstParty() != AiChatConfirmation.UNKNOWN) {
            session.setSenderFirstPartyConfirmed(decision.firstParty() == AiChatConfirmation.YES);
        }

        if (decision.payerBankType() != AiDecisionBankType.UNKNOWN) {
            session.setPayerBankName(StringUtils.hasText(decision.payerBankName())
                    ? limit(decision.payerBankName().trim(), 128)
                    : bankTitle(decision.payerBankType()));
            boolean compatible = compatiblePayerBank(
                    PayerBankType.effective(session.getWithdrawalRequest().getPayerBankType()),
                    decision.payerBankType()
            );
            session.setPayerBankConfirmed(compatible);
            boolean optionalReceipt = PayerBankType.effective(session.getWithdrawalRequest().getPayerBankType())
                    == PayerBankType.ANY_BANK
                    && decision.payerBankType() == AiDecisionBankType.TBANK;
            if (session.isOptionalReceiptEmail() != optionalReceipt) {
                session.setReceiptEmailConfirmed(null);
            }
            session.setOptionalReceiptEmail(optionalReceipt);
            if (!optionalReceipt && !session.isRequiredReceiptEmail()) {
                session.setAutoReceiptEnabled(false);
            }
        }

        if ((session.isRequiredReceiptEmail() || session.isOptionalReceiptEmail())
                && decision.receiptEmail() != AiChatConfirmation.UNKNOWN) {
            boolean confirmed = decision.receiptEmail() == AiChatConfirmation.YES;
            session.setReceiptEmailConfirmed(confirmed);
            if (session.isOptionalReceiptEmail()) {
                session.setAutoReceiptEnabled(confirmed);
            }
        }

        if (decision.thirdPartyTransfer() != AiChatConfirmation.UNKNOWN) {
            session.setThirdPartyTransferConfirmed(decision.thirdPartyTransfer() == AiChatConfirmation.YES);
        }
        if (decision.paymentClaim() == AiPaymentClaim.PAYMENT_SENT) {
            session.setPaymentActuallySentClaimed(true);
        }
    }

    private ObservedFactsSnapshot observedFactsSnapshot(AiChatSessionEntity session) {
        return new ObservedFactsSnapshot(
                session.getSenderFirstPartyConfirmed(),
                session.getPayerBankConfirmed(),
                session.getPayerBankName(),
                session.getReceiptEmailConfirmed(),
                session.getThirdPartyTransferConfirmed(),
                session.isOptionalReceiptEmail(),
                session.isAutoReceiptEnabled(),
                session.isPaymentActuallySentClaimed()
        );
    }

    private void restoreObservedFacts(AiChatSessionEntity session, ObservedFactsSnapshot snapshot) {
        session.setSenderFirstPartyConfirmed(snapshot.senderFirstPartyConfirmed());
        session.setPayerBankConfirmed(snapshot.payerBankConfirmed());
        session.setPayerBankName(snapshot.payerBankName());
        session.setReceiptEmailConfirmed(snapshot.receiptEmailConfirmed());
        session.setThirdPartyTransferConfirmed(snapshot.thirdPartyTransferConfirmed());
        session.setOptionalReceiptEmail(snapshot.optionalReceiptEmail());
        session.setAutoReceiptEnabled(snapshot.autoReceiptEnabled());
        session.setPaymentActuallySentClaimed(snapshot.paymentActuallySentClaimed());
    }

    private AiChatDecision normalizeDecision(AiChatDecision decision) {
        List<String> messages = decision.messages() == null
                ? List.of()
                : decision.messages().stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .toList();
        return new AiChatDecision(
                decision.action(),
                messages,
                nullToEmpty(decision.finalWarning()).trim(),
                decision.firstParty(),
                decision.payerBankType(),
                nullToEmpty(decision.payerBankName()).trim(),
                decision.receiptEmail(),
                decision.thirdPartyTransfer(),
                decision.paymentClaim(),
                nullToEmpty(decision.handoffReason()).trim(),
                nullToEmpty(decision.summary()).trim()
        );
    }

    private AiChatDecision polishDecision(AiChatTrigger trigger, AiChatDecision decision) {
        if (trigger != AiChatTrigger.START || decision.messages().isEmpty()) {
            return decision;
        }

        List<String> messages = new ArrayList<>(decision.messages());
        String firstMessage = stripRedundantStartGreeting(messages.getFirst());
        if (StringUtils.hasText(firstMessage)) {
            messages.set(0, firstMessage);
        } else {
            messages.removeFirst();
        }
        if (messages.equals(decision.messages())) {
            return decision;
        }

        return new AiChatDecision(
                decision.action(),
                List.copyOf(messages),
                decision.finalWarning(),
                decision.firstParty(),
                decision.payerBankType(),
                decision.payerBankName(),
                decision.receiptEmail(),
                decision.thirdPartyTransfer(),
                decision.paymentClaim(),
                decision.handoffReason(),
                decision.summary()
        );
    }

    private String stripRedundantStartGreeting(String message) {
        return nullToEmpty(message)
                .replaceFirst(
                        "(?iu)^\\s*(привет|здравствуйте|добрый\\s+(день|вечер)|доброе\\s+утро)[!.,:;\\s-]*",
                        ""
                )
                .trim();
    }

    private Optional<String> validateDecision(
            AiChatSessionEntity session,
            AiChatTrigger trigger,
            AiChatDecision decision,
            List<ChatMessageLogResponse> allMessages,
            Set<String> newMessageIds
    ) {
        boolean counterpartyRequestedCancellation = counterpartyRequestedCancellation(allMessages, newMessageIds);
        boolean paymentAlreadyClaimed = session.isPaymentActuallySentClaimed()
                || counterpartyPaymentProofAttached(session, allMessages, newMessageIds);
        if (decision.action() == null) {
            return Optional.of("не указано действие");
        }
        if (counterpartyRequestedCancellation
                && decision.action() != AiChatAction.REQUEST_CANCELLATION
                && decision.action() != AiChatAction.HANDOFF) {
            return Optional.of("контрагент просит отмену или сообщает, что не сможет оплатить");
        }
        if (paymentAlreadyClaimed && asksWhetherPaymentSent(decision.messages())) {
            return Optional.of("контрагент уже заявил оплату или приложил чек, не нужно спрашивать, отправлен ли перевод");
        }
        if (decision.messages().size() > properties.getMaxMessagesPerDecision()) {
            return Optional.of("слишком много сообщений в одном ответе");
        }
        if (decision.messages().stream().anyMatch(message -> message.length() > properties.getMaxMessageLength())) {
            return Optional.of("одно из сообщений слишком длинное");
        }
        if (decision.action() == AiChatAction.WAIT
                && (!decision.messages().isEmpty() || StringUtils.hasText(decision.finalWarning()))) {
            return Optional.of("действие WAIT не может содержать сообщения");
        }
        if (decision.action() != AiChatAction.WAIT && decision.messages().isEmpty()) {
            return Optional.of("для выбранного действия отсутствуют сообщения контрагенту");
        }
        if (decision.action() != AiChatAction.SEND_REQUISITES && StringUtils.hasText(decision.finalWarning())) {
            return Optional.of("finalWarning допустим только для SEND_REQUISITES");
        }
        if (decision.action() == AiChatAction.SEND_REQUISITES) {
            if (requisitesSent(session)) {
                return Optional.of("реквизиты уже были отправлены ранее");
            }
            if (!allRequiredConfirmationsReceived(session)) {
                return Optional.of("реквизиты запрошены до всех обязательных подтверждений");
            }
            if (!validFinalWarning(decision.finalWarning())) {
                return Optional.of("предупреждение не объясняет риск перевода не на тот банк и невозможность помочь");
            }
        }
        if (!requisitesSent(session) && containsProtectedRequisites(session, decision.messages())) {
            return Optional.of("обычное сообщение содержит реквизиты до разрешённой выдачи");
        }
        if (!Boolean.TRUE.equals(session.getPayerBankConfirmed())
                && decision.payerBankType() == AiDecisionBankType.UNKNOWN
                && asksThirdPartyTransfer(decision.messages())) {
            return Optional.of("нельзя переходить к 3 лицу, не зафиксировав банк отправителя в payerBankType");
        }
        if (!requisitesSent(session)
                && allRequiredConfirmationsReceived(session)
                && decision.action() == AiChatAction.SEND_MESSAGES
                && promisesRequisites(decision.messages())) {
            return Optional.of("все условия подтверждены, обещание скинуть реквизиты нужно заменить на SEND_REQUISITES");
        }
        if (hasBlockingRejection(session) && !requisitesSent(session)
                && decision.action() != AiChatAction.REQUEST_CANCELLATION) {
            return Optional.of("обязательное условие отклонено, нужно запросить самостоятельную отмену ордера");
        }
        if (decision.action() == AiChatAction.REQUEST_CANCELLATION
                && !hasBlockingRejection(session)
                && session.getStatus() != AiChatSessionStatus.WAITING_CANCEL
                && !requisitesSent(session)
                && !counterpartyRequestedCancellation) {
            return Optional.of("нет подтверждённой причины просить отмену ордера");
        }
        if (trigger == AiChatTrigger.INVALID_RECEIPT && decision.action() != AiChatAction.HANDOFF) {
            return Optional.of("невалидный чек требует сообщения контрагенту и передачи оператору");
        }
        if (decision.action() == AiChatAction.HANDOFF && !StringUtils.hasText(decision.handoffReason())) {
            return Optional.of("для передачи оператору не указана причина");
        }
        return Optional.empty();
    }

    private void executeDecision(AiChatSessionEntity session, AiChatDecision decision) {
        if (decision.action() == AiChatAction.WAIT) {
            clearSuggestion(session);
            return;
        }

        synchronizeModeBeforeDispatch(session);
        if (session.getMode() == AiChatAgentMode.DISABLED) {
            return;
        }
        List<String> messages = outgoingMessages(session, decision);
        if (session.getMode() == AiChatAgentMode.DRY_RUN) {
            prepareSuggestion(session, messages, decision);
            if (decision.action() == AiChatAction.REQUEST_CANCELLATION) {
                session.setStatus(AiChatSessionStatus.WAITING_CANCEL);
                session.setCurrentStep(AiChatStep.WAITING_CANCEL);
                notifyOperator(session, "Контрагент не прошёл обязательное условие заявки");
            } else if (decision.action() == AiChatAction.HANDOFF) {
                markOperatorRequiredForDryRun(session, decision.handoffReason());
            }
            return;
        }

        if (!sendNow(session, messages)) {
            return;
        }
        applySentAction(session, decision.action(), decision.finalWarning());
    }

    private void synchronizeModeBeforeDispatch(AiChatSessionEntity session) {
        if (session.getId() == null) {
            return;
        }
        String persistedMode = jdbcTemplate.queryForObject(
                "select mode from ai_chat_sessions where id = ? for update",
                String.class,
                session.getId()
        );
        if (!StringUtils.hasText(persistedMode)) {
            return;
        }
        try {
            session.setMode(AiChatAgentMode.valueOf(persistedMode));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown persisted AI chat mode: " + persistedMode, exception);
        }
    }

    private List<String> outgoingMessages(AiChatSessionEntity session, AiChatDecision decision) {
        if (decision.action() != AiChatAction.SEND_REQUISITES) {
            return decision.messages();
        }
        List<String> messages = new ArrayList<>(decision.messages());
        if (StringUtils.hasText(decision.finalWarning())) {
            messages.add(decision.finalWarning());
        }
        messages.addAll(chatService.requisiteMessages(session.getWithdrawalRequest()));
        if ((session.isRequiredReceiptEmail() || session.isAutoReceiptEnabled())
                && StringUtils.hasText(session.getWorkspace().getReceiptEmail())) {
            messages.add(session.getWorkspace().getReceiptEmail());
        }
        return messages;
    }

    private boolean counterpartyRequestedCancellation(
            List<ChatMessageLogResponse> allMessages,
            Set<String> newMessageIds
    ) {
        if (newMessageIds.isEmpty()) {
            return false;
        }
        return allMessages.stream()
                .filter(message -> message.senderType() == ChatMessageSenderType.COUNTERPARTY)
                .filter(message -> newMessageIds.contains(message.id()))
                .map(this::messageText)
                .map(this::normalizeDialogText)
                .anyMatch(text -> COUNTERPARTY_CANCEL_PHRASES.stream().anyMatch(text::contains));
    }

    private boolean counterpartyPaymentProofAttached(
            AiChatSessionEntity session,
            List<ChatMessageLogResponse> allMessages,
            Set<String> newMessageIds
    ) {
        if (newMessageIds.isEmpty()
                || (!requisitesSent(session)
                && session.getWithdrawalRequest().getStatus() != WithdrawalStatus.PAYMENT_VERIFICATION)) {
            return false;
        }
        return allMessages.stream()
                .filter(message -> message.senderType() == ChatMessageSenderType.COUNTERPARTY)
                .filter(message -> newMessageIds.contains(message.id()))
                .map(ChatMessageLogResponse::content)
                .filter(Objects::nonNull)
                .map(ChatMessageContentResponse::type)
                .anyMatch(type -> type == ChatMessageContentType.IMAGE
                        || type == ChatMessageContentType.PDF
                        || type == ChatMessageContentType.UNKNOWN);
    }

    private boolean asksWhetherPaymentSent(List<String> messages) {
        return messages.stream()
                .map(this::normalizeDialogText)
                .anyMatch(message -> message.contains("?")
                        && (message.contains("перевод") || message.contains("оплат"))
                        && (message.contains("отправ") || message.contains("оплатил") || message.contains("оплатили"))
                        && (message.contains("уже") || message.contains("точно")));
    }

    private boolean asksThirdPartyTransfer(List<String> messages) {
        return messages.stream()
                .map(this::normalizeDialogText)
                .anyMatch(message -> message.contains("3 лица")
                        || message.contains("третьего лица")
                        || message.contains("третье лицо"));
    }

    private boolean promisesRequisites(List<String> messages) {
        return messages.stream()
                .map(this::normalizeDialogText)
                .anyMatch(message -> message.contains("реквиз")
                        && (message.contains("скин") || message.contains("отправ") || message.contains("дам")));
    }

    private String normalizeDialogText(String value) {
        return nullToEmpty(value)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean sendNow(AiChatSessionEntity session, List<String> messages) {
        boolean sent = chatService.sendAgentMessages(
                session.getWorkspace(),
                session.getWithdrawalRequest(),
                messages
        );
        if (!sent) {
            requireOperator(session, "Не удалось отправить сообщение ИИ в чат Bybit");
        }
        return sent;
    }

    private void applySentAction(AiChatSessionEntity session, AiChatAction action, String finalWarning) {
        switch (action) {
            case SEND_MESSAGES -> {
            }
            case SEND_REQUISITES -> markRequisitesSent(session, finalWarning);
            case REQUEST_CANCELLATION -> {
                session.setStatus(AiChatSessionStatus.WAITING_CANCEL);
                session.setCurrentStep(AiChatStep.WAITING_CANCEL);
                notifyOperator(session, "Контрагент не прошёл обязательное условие заявки");
            }
            case HANDOFF -> requireOperator(
                    session,
                    StringUtils.hasText(session.getOperatorHandoffReason())
                            ? session.getOperatorHandoffReason()
                            : "ИИ передал диалог оператору"
            );
            case WAIT -> {
            }
        }
    }

    private void markRequisitesSent(AiChatSessionEntity session, String finalWarning) {
        Instant now = Instant.now(clock);
        session.setFinalWarningSent(StringUtils.hasText(finalWarning));
        session.setRequisitesSentAt(now);
        session.setStatus(AiChatSessionStatus.REQUISITES_SENT);
        session.setCurrentStep(AiChatStep.REQUISITES_SENT);
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        withdrawal.setRequisitesSentAt(now);
        withdrawalRepository.save(withdrawal);
        eventService.add(withdrawal, WithdrawalEventType.REQUISITES_SENT, "Requisites sent by AI chat agent");
    }

    private void prepareSuggestion(
            AiChatSessionEntity session,
            List<String> messages,
            AiChatDecision decision
    ) {
        try {
            session.setSuggestedMessagesJson(objectMapper.writeValueAsString(messages));
            session.setSuggestedReason(decision.summary());
            session.setSuggestedAt(Instant.now(clock));
            session.setSuggestedAction(decision.action());
            session.setSuggestedFinalWarning(decision.finalWarning());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize AI chat suggestion", exception);
        }
    }

    private void clearSuggestion(AiChatSessionEntity session) {
        session.setSuggestedMessagesJson(null);
        session.setSuggestedReason(null);
        session.setSuggestedAt(null);
        session.setSuggestedAction(null);
        session.setSuggestedFinalWarning(null);
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

    private void requireOperator(AiChatSessionEntity session, String reason) {
        session.setMode(AiChatAgentMode.DISABLED);
        session.setStatus(AiChatSessionStatus.OPERATOR_REQUIRED);
        session.setCurrentStep(AiChatStep.OPERATOR_HANDOFF);
        session.setOperatorRequiredAt(Instant.now(clock));
        session.setOperatorHandoffReason(reason);
        session.setLastDecisionSummary(reason);
        clearSuggestion(session);
        notifyOperator(session, reason);
        touch(session);
    }

    private void markOperatorRequiredForDryRun(AiChatSessionEntity session, String reason) {
        session.setStatus(AiChatSessionStatus.OPERATOR_REQUIRED);
        session.setCurrentStep(AiChatStep.OPERATOR_HANDOFF);
        session.setOperatorRequiredAt(Instant.now(clock));
        session.setOperatorHandoffReason(reason);
        notifyOperator(session, reason);
    }

    private void notifyOperator(AiChatSessionEntity session, String reason) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        boolean newAttention = !withdrawal.isAttentionRequired()
                || !Objects.equals(withdrawal.getLastWarning(), reason);
        withdrawal.setAttentionRequired(true);
        withdrawal.setLastWarning(reason);
        withdrawalRepository.save(withdrawal);
        if (newAttention) {
            eventService.add(withdrawal, WithdrawalEventType.AI_CHAT_OPERATOR_REQUIRED, reason);
        }
        touch(session);
    }

    private void complete(AiChatSessionEntity session, String reason) {
        session.setMode(AiChatAgentMode.DISABLED);
        session.setStatus(AiChatSessionStatus.COMPLETED);
        session.setCurrentStep(AiChatStep.COMPLETED);
        session.setLastDecisionSummary(reason);
        clearSuggestion(session);
        touch(session);
        sessionRepository.save(session);
    }

    private List<ChatMessageLogResponse> readChatOrHandoff(AiChatSessionEntity session) {
        try {
            return chatService.getMessages(session.getWorkspace(), session.getWithdrawalRequest());
        } catch (RuntimeException exception) {
            requireOperator(session, "Не удалось прочитать чат Bybit: " + safeError(exception));
            return null;
        }
    }

    private List<ChatMessageLogResponse> newCounterpartyMessages(
            AiChatSessionEntity session,
            List<ChatMessageLogResponse> allMessages
    ) {
        List<ChatMessageLogResponse> counterpart = counterpartMessages(allMessages);
        if (counterpart.isEmpty()) {
            return List.of();
        }
        if (StringUtils.hasText(session.getLastProcessedMessageId())) {
            for (int index = 0; index < counterpart.size(); index++) {
                if (Objects.equals(counterpart.get(index).id(), session.getLastProcessedMessageId())) {
                    return List.copyOf(counterpart.subList(index + 1, counterpart.size()));
                }
            }
        }
        Instant lastCreatedAt = session.getLastProcessedMessageCreatedAt();
        if (lastCreatedAt == null) {
            return counterpart.stream()
                    .filter(message -> message.createdAt() == null
                            || !message.createdAt().isBefore(session.getCreatedAt()))
                    .toList();
        }
        return counterpart.stream()
                .filter(message -> message.createdAt() == null || message.createdAt().isAfter(lastCreatedAt))
                .toList();
    }

    private List<ChatMessageLogResponse> counterpartMessages(List<ChatMessageLogResponse> messages) {
        return messages.stream()
                .filter(message -> message.senderType() == ChatMessageSenderType.COUNTERPARTY)
                .sorted(messageComparator())
                .toList();
    }

    private boolean operatorAlreadyStartedConversation(List<ChatMessageLogResponse> messages) {
        return messages.stream()
                .filter(message -> message.senderType() == ChatMessageSenderType.USER
                        || message.senderType() == ChatMessageSenderType.BOT)
                .filter(message -> message.content() != null)
                .filter(message -> message.content().type() == ChatMessageContentType.TEXT)
                .map(this::messageText)
                .map(this::normalizeDialogText)
                .filter(StringUtils::hasText)
                .anyMatch(message -> !looksLikeManagedAdText(message));
    }

    private boolean looksLikeManagedAdText(String message) {
        return message.contains("принимаю платеж")
                && (message.contains("заходите") || message.contains("принимаю на карту"));
    }

    private Comparator<ChatMessageLogResponse> messageComparator() {
        return Comparator.comparing(
                ChatMessageLogResponse::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())
        ).thenComparing(ChatMessageLogResponse::id, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private void rememberProcessed(AiChatSessionEntity session, ChatMessageLogResponse message) {
        if (message == null) {
            return;
        }
        session.setLastProcessedMessageId(message.id());
        session.setLastProcessedMessageCreatedAt(message.createdAt());
    }

    private boolean inactivityReminderDue(
            AiChatSessionEntity session,
            List<ChatMessageLogResponse> messages
    ) {
        if (!requisitesSent(session)
                || session.getWithdrawalRequest().getStatus() != WithdrawalStatus.PAYMENT_IN_PROGRESS) {
            return false;
        }
        Instant reference = session.getRequisitesSentAt();
        ChatMessageLogResponse latestCounterparty = lastMessage(counterpartMessages(messages));
        if (latestCounterparty != null && latestCounterparty.createdAt() != null
                && latestCounterparty.createdAt().isAfter(reference)) {
            reference = latestCounterparty.createdAt();
        }
        if (session.getLastInactivityReminderAt() != null
                && !session.getLastInactivityReminderAt().isBefore(reference)) {
            return false;
        }
        return elapsed(reference, properties.getInactivityReminderDelay());
    }

    private boolean paymentVerificationReminderDue(AiChatSessionEntity session) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        if (withdrawal.getStatus() != WithdrawalStatus.PAYMENT_VERIFICATION
                || !session.isAutoReceiptEnabled()
                || session.getPaymentVerificationReminderSentAt() != null) {
            return false;
        }
        boolean validReceiptExists = receiptCheckRepository
                .findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
                        withdrawal.getId(),
                        withdrawal.getBybitOrderId(),
                        ReceiptVerificationStatus.VERIFIED
                )
                .isPresent();
        if (validReceiptExists) {
            return false;
        }
        Instant startedAt = withdrawal.getVerificationStartedAt() == null
                ? withdrawal.getPaidAt()
                : withdrawal.getVerificationStartedAt();
        return elapsed(startedAt, properties.getPaymentVerificationReminderDelay());
    }

    private Optional<EmailReceiptCheckEntity> newFailedReceipt(AiChatSessionEntity session) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        Optional<EmailReceiptCheckEntity> failed = receiptCheckRepository
                .findFirstByWithdrawalRequest_IdAndBybitOrderIdAndVerificationStatusOrderByCreatedAtDescIdDesc(
                        withdrawal.getId(),
                        withdrawal.getBybitOrderId(),
                        ReceiptVerificationStatus.FAILED
                );
        return failed.filter(check -> !Objects.equals(check.getId(), session.getLastReceiptCheckIdHandled()));
    }

    private boolean elapsed(Instant startedAt, Duration delay) {
        return startedAt != null && !Instant.now(clock).isBefore(startedAt.plus(delay));
    }

    private AiChatStep initialStep(WithdrawalRequestEntity withdrawal) {
        return withdrawal.isRequireSenderFirstParty()
                ? AiChatStep.SENDER_FIRST_PARTY
                : AiChatStep.PAYER_BANK;
    }

    private AiChatStep nextRequiredStep(AiChatSessionEntity session) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        if (requisitesSent(session)) {
            return AiChatStep.REQUISITES_SENT;
        }
        if (withdrawal.isRequireSenderFirstParty()
                && !Boolean.TRUE.equals(session.getSenderFirstPartyConfirmed())) {
            return AiChatStep.SENDER_FIRST_PARTY;
        }
        if (!Boolean.TRUE.equals(session.getPayerBankConfirmed())) {
            return AiChatStep.PAYER_BANK;
        }
        if (session.isRequiredReceiptEmail() && !Boolean.TRUE.equals(session.getReceiptEmailConfirmed())) {
            return AiChatStep.REQUIRED_RECEIPT_EMAIL;
        }
        if (session.isOptionalReceiptEmail() && session.getReceiptEmailConfirmed() == null) {
            return AiChatStep.OPTIONAL_RECEIPT_EMAIL;
        }
        if (withdrawal.isThirdPartyTransfer()
                && !Boolean.TRUE.equals(session.getThirdPartyTransferConfirmed())) {
            return AiChatStep.THIRD_PARTY_TRANSFER;
        }
        return AiChatStep.FINAL_WARNING;
    }

    private boolean allRequiredConfirmationsReceived(AiChatSessionEntity session) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        return (!withdrawal.isRequireSenderFirstParty()
                || Boolean.TRUE.equals(session.getSenderFirstPartyConfirmed()))
                && Boolean.TRUE.equals(session.getPayerBankConfirmed())
                && (!session.isRequiredReceiptEmail()
                || Boolean.TRUE.equals(session.getReceiptEmailConfirmed()))
                && (!withdrawal.isThirdPartyTransfer()
                || Boolean.TRUE.equals(session.getThirdPartyTransferConfirmed()));
    }

    private boolean hasBlockingRejection(AiChatSessionEntity session) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        return withdrawal.isRequireSenderFirstParty()
                && Boolean.FALSE.equals(session.getSenderFirstPartyConfirmed())
                || Boolean.FALSE.equals(session.getPayerBankConfirmed())
                || session.isRequiredReceiptEmail() && Boolean.FALSE.equals(session.getReceiptEmailConfirmed())
                || withdrawal.isThirdPartyTransfer()
                && Boolean.FALSE.equals(session.getThirdPartyTransferConfirmed());
    }

    private boolean compatiblePayerBank(PayerBankType requirement, AiDecisionBankType actual) {
        return switch (requirement) {
            case TBANK_AUTO -> actual == AiDecisionBankType.TBANK;
            case SBERBANK -> actual == AiDecisionBankType.SBERBANK;
            case ANY_BANK -> actual != AiDecisionBankType.UNKNOWN;
        };
    }

    private boolean validFinalWarning(String warning) {
        if (!StringUtils.hasText(warning)) {
            return false;
        }
        String value = warning.toLowerCase(Locale.ROOT);
        boolean explainsLoss = value.contains("потер") || value.contains("пропад") || value.contains("утрат");
        boolean cannotHelp = value.contains("не смогу") || value.contains("не можем") || value.contains("невозможно");
        return value.contains("банк") && explainsLoss && cannotHelp;
    }

    private boolean containsProtectedRequisites(AiChatSessionEntity session, List<String> messages) {
        WithdrawalRequestEntity withdrawal = session.getWithdrawalRequest();
        List<String> protectedValues = List.of(
                nullToEmpty(withdrawal.getRecipientPhone()),
                nullToEmpty(withdrawal.getRecipientCardNumber()),
                nullToEmpty(withdrawal.getRecipientAccountNumber()),
                nullToEmpty(withdrawal.getRecipientName()),
                nullToEmpty(session.getWorkspace().getReceiptEmail())
        );
        for (String message : messages) {
            String normalizedMessage = normalizeComparable(message);
            for (String protectedValue : protectedValues) {
                String normalizedValue = normalizeComparable(protectedValue);
                if (normalizedValue.length() >= 4 && normalizedMessage.contains(normalizedValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalizeComparable(String value) {
        return nullToEmpty(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private boolean requisitesSent(AiChatSessionEntity session) {
        return session.getRequisitesSentAt() != null
                || session.getWithdrawalRequest().getRequisitesSentAt() != null;
    }

    private boolean requiredReceiptEmail(WithdrawalRequestEntity withdrawal) {
        return WithdrawalPaymentRules.isAutoReleaseEnabled(
                withdrawal.getPayerBankType(),
                withdrawal.getWithdrawalMethod()
        );
    }

    private AiChatAgentResponse toResponse(AiChatSessionEntity session) {
        return new AiChatAgentResponse(
                true,
                session.getMode(),
                session.getMode().getTitle(),
                session.getStatus().name(),
                session.getStatus().getTitle(),
                session.getCurrentStep().name(),
                session.getCurrentStep().getTitle(),
                session.isAutoReceiptEnabled(),
                session.getStatus() == AiChatSessionStatus.OPERATOR_REQUIRED,
                suggestedMessages(session),
                session.getSuggestedReason(),
                session.getSuggestedAt(),
                session.getLastDecisionSummary(),
                session.getLastAction(),
                session.getConversationSummary(),
                session.getSummaryUpdatedAt(),
                session.getOperatorHandoffReason()
        );
    }

    private Set<String> messageIds(List<ChatMessageLogResponse> messages) {
        Set<String> ids = new LinkedHashSet<>();
        messages.stream().map(ChatMessageLogResponse::id).filter(Objects::nonNull).forEach(ids::add);
        return Set.copyOf(ids);
    }

    private ChatMessageLogResponse lastMessage(List<ChatMessageLogResponse> messages) {
        return messages.isEmpty() ? null : messages.getLast();
    }

    private void touch(AiChatSessionEntity session) {
        session.setUpdatedAt(Instant.now(clock));
    }

    private String amountText(WithdrawalRequestEntity withdrawal) {
        BigDecimal amount = withdrawal.getBybitOrderAmountRub() == null
                ? withdrawal.getAmountRub()
                : withdrawal.getBybitOrderAmountRub();
        if (amount == null) {
            return "сумма ордера";
        }
        return amount.stripTrailingZeros().toPlainString() + " RUB";
    }

    private String bankTitle(AiDecisionBankType bankType) {
        return switch (bankType) {
            case TBANK -> "Т-Банк";
            case SBERBANK -> "Сбербанк";
            case OTHER -> "Другой банк";
            case UNKNOWN -> "";
        };
    }

    private String instantText(Instant value) {
        return value == null ? "" : value.toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize AI application context", exception);
        }
    }

    private String safeError(RuntimeException exception) {
        return StringUtils.hasText(exception.getMessage())
                ? limit(exception.getMessage(), 500)
                : exception.getClass().getSimpleName();
    }

    private record ConversationWindow(List<ChatMessageLogResponse> messages) {
    }

    private record ObservedFactsSnapshot(
            Boolean senderFirstPartyConfirmed,
            Boolean payerBankConfirmed,
            String payerBankName,
            Boolean receiptEmailConfirmed,
            Boolean thirdPartyTransferConfirmed,
            boolean optionalReceiptEmail,
            boolean autoReceiptEnabled,
            boolean paymentActuallySentClaimed
    ) {
    }
}
