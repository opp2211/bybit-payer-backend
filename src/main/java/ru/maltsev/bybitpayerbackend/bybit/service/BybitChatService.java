package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageContentType;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageSenderType;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitBotChatMessageEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitChatMessage;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitBotChatMessageRepository;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalPaymentRules;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceAccessService;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceBybitIdentity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceBybitIdentityService;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;

@Service
@Slf4j
public class BybitChatService {

    private static final String HELLO_MESSAGE = "\u041f\u0440\u0438\u0432\u0435\u0442";
    private static final String TEXT_CONTENT_TYPE = "str";

    private final WithdrawalRequestRepository withdrawalRepository;
    private final WithdrawalEventService eventService;
    private final BybitGateway bybitGateway;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final WorkspaceSecretService workspaceSecretService;
    private final WorkspaceAccessService workspaceAccessService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final BybitBotChatMessageRepository botMessageRepository;
    private final WorkspaceBybitIdentityService workspaceBybitIdentityService;
    private final BybitChatMessageFormatter formatter;
    private final BusinessProperties businessProperties;
    private final Clock clock;
    private final ConcurrentHashMap<ChatCacheKey, ChatCacheEntry> chatCache = new ConcurrentHashMap<>();

    @Autowired
    public BybitChatService(
            WithdrawalRequestRepository withdrawalRepository,
            WithdrawalEventService eventService,
            BybitGateway bybitGateway,
            BybitCredentialsContext bybitCredentialsContext,
            WorkspaceSecretService workspaceSecretService,
            WorkspaceAccessService workspaceAccessService,
            CurrentUserService currentUserService,
            AuditService auditService,
            BybitBotChatMessageRepository botMessageRepository,
            WorkspaceBybitIdentityService workspaceBybitIdentityService,
            BybitChatMessageFormatter formatter,
            BusinessProperties businessProperties,
            Clock clock
    ) {
        this.withdrawalRepository = withdrawalRepository;
        this.eventService = eventService;
        this.bybitGateway = bybitGateway;
        this.bybitCredentialsContext = bybitCredentialsContext;
        this.workspaceSecretService = workspaceSecretService;
        this.workspaceAccessService = workspaceAccessService;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.botMessageRepository = botMessageRepository;
        this.workspaceBybitIdentityService = workspaceBybitIdentityService;
        this.formatter = formatter;
        this.businessProperties = businessProperties;
        this.clock = clock;
    }

    public BybitChatService(
            WithdrawalRequestRepository withdrawalRepository,
            WithdrawalEventService eventService,
            BybitGateway bybitGateway,
            BusinessProperties businessProperties,
            Clock clock
    ) {
        this(
                withdrawalRepository,
                eventService,
                bybitGateway,
                new BybitCredentialsContext(),
                null,
                null,
                null,
                null,
                null,
                null,
                new BybitChatMessageFormatter(new BybitProperties()),
                businessProperties,
                clock
        );
    }

    @Transactional
    public void sendRequisites(WithdrawalRequestEntity withdrawal) {
        sendRequisites(withdrawal.getWorkspace(), withdrawal, WithdrawalPaymentRules.isAutoReleaseEnabled(
                withdrawal.getPayerBankType(),
                withdrawal.getWithdrawalMethod()
        ));
    }

    @Transactional
    public void sendRequisites(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal, boolean includeReceiptEmail) {
        List<String> messages = new ArrayList<>();
        messages.add(HELLO_MESSAGE);
        messages.addAll(requisiteMessages(withdrawal));

        if (includeReceiptEmail && workspace != null && StringUtils.hasText(workspace.getReceiptEmail())) {
            messages.add(workspace.getReceiptEmail());
        }

        boolean allSent = sendAgentMessages(workspace, withdrawal, messages);

        if (allSent) {
            withdrawal.setRequisitesSentAt(Instant.now(clock));
            eventService.add(withdrawal, WithdrawalEventType.REQUISITES_SENT, "Requisites sent to Bybit order chat");
            log.info(
                    "Bybit chat requisites sent: orderId={}, withdrawalId={}, messages={}",
                    withdrawal.getBybitOrderId(),
                    withdrawal.getId(),
                    messages.size()
            );
        } else {
            withdrawal.setAttentionRequired(true);
            withdrawal.setLastWarning("Not all chat messages were sent");
            eventService.add(withdrawal, WithdrawalEventType.ATTENTION_REQUIRED, "Not all chat messages were sent");
            log.warn(
                    "Bybit chat requisites were not fully sent: orderId={}, withdrawalId={}",
                    withdrawal.getBybitOrderId(),
                    withdrawal.getId()
            );
        }
        withdrawalRepository.save(withdrawal);
    }

    public List<String> requisiteMessages(WithdrawalRequestEntity withdrawal) {
        WithdrawalMethod withdrawalMethod = WithdrawalMethod.effective(withdrawal.getWithdrawalMethod());
        return switch (withdrawalMethod) {
            case SBP -> List.of(
                    withdrawal.getRecipientPhone(),
                    withdrawal.getRecipientBank().getTitle() + ", " + withdrawal.getRecipientName()
            );
            case CARD_NUMBER -> {
                List<String> messages = new ArrayList<>();
                messages.add(withdrawal.getRecipientCardNumber());
                if (StringUtils.hasText(withdrawal.getRecipientName())) {
                    messages.add(withdrawal.getRecipientName());
                }
                yield messages;
            }
            case ACCOUNT_NUMBER -> List.of(
                    withdrawal.getRecipientAccountNumber(),
                    withdrawal.getRecipientName()
            );
        };
    }

    @Transactional
    public boolean sendAgentMessages(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal, List<String> messages) {
        WorkspaceEntity effectiveWorkspace = effectiveWorkspace(workspace, withdrawal);
        boolean allSent = true;
        for (String rawMessage : messages) {
            if (!StringUtils.hasText(rawMessage)) {
                continue;
            }
            String message = rawMessage.trim();
            SentChatMessage sentMessage;
            if (effectiveWorkspace != null && workspaceSecretService != null) {
                sentMessage = bybitCredentialsContext.callWith(
                        workspaceSecretService.bybitCredentials(effectiveWorkspace),
                        () -> sendBybitMessage(withdrawal, message)
                );
            } else {
                sentMessage = sendBybitMessage(withdrawal, message);
            }
            if (sentMessage == null) {
                allSent = false;
            } else {
                storeBotMessage(effectiveWorkspace, withdrawal, sentMessage);
                appendSentMessageToCache(effectiveWorkspace, withdrawal, sentMessage);
            }
            sleepBetweenMessages();
        }
        return allSent;
    }

    @Transactional
    public void sendMessage(Long withdrawalId, String rawMessage) {
        WithdrawalRequestEntity withdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> BusinessException.conflict("Withdrawal not found"));
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            throw BusinessException.conflict("Bybit order is not linked to withdrawal yet");
        }

        String message = normalizedMessage(rawMessage);
        SentChatMessage sentMessage = sendBybitMessage(withdrawal, message);
        if (sentMessage == null) {
            throw BusinessException.conflict("Failed to send message to Bybit chat");
        }
        appendSentMessageToCache(withdrawal.getWorkspace(), withdrawal, sentMessage);
        eventService.add(withdrawal, WithdrawalEventType.CHAT_MESSAGE_SENT, "Chat message sent by operator");
    }

    @Transactional
    public void sendMessage(String workspacePublicId, String withdrawalPublicId, String rawMessage) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        WithdrawalRequestEntity withdrawal = withdrawalRepository.findByWorkspaceAndPublicId(workspace, withdrawalPublicId)
                .orElseThrow(() -> BusinessException.conflict("Withdrawal not found"));
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            throw BusinessException.conflict("Bybit order is not linked to withdrawal yet");
        }

        String message = normalizedMessage(rawMessage);
        SentChatMessage sentMessage = bybitCredentialsContext.callWith(
                workspaceSecretService.bybitCredentials(workspace),
                () -> sendBybitMessage(withdrawal, message)
        );
        if (sentMessage == null) {
            throw BusinessException.conflict("Failed to send message to Bybit chat");
        }
        appendSentMessageToCache(workspace, withdrawal, sentMessage);
        eventService.add(withdrawal, WithdrawalEventType.CHAT_MESSAGE_SENT, "Chat message sent by operator", currentUser);
        auditService.add(currentUser, workspace, "BYBIT_CHAT_MESSAGE_SENT", "WITHDRAWAL", withdrawal.getPublicId(), null);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageLogResponse> getMessages(WithdrawalRequestEntity withdrawal) {
        return getMessages(null, withdrawal);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageLogResponse> getMessages(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            return List.of();
        }

        List<BybitChatMessage> remoteMessages = getRawMessages(workspace, withdrawal);
        WorkspaceBybitIdentity identity = resolveIdentity(workspace);
        Set<String> botMessageUuids = botMessageUuids(workspace, withdrawal.getBybitOrderId(), remoteMessages);

        return remoteMessages.stream()
                .filter(message -> !message.hidden())
                .map(message -> formatter.toResponse(message, identity, botMessageUuids))
                .sorted(Comparator.comparing(
                        ChatMessageLogResponse::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ).thenComparing(ChatMessageLogResponse::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BybitChatMessage> getCounterpartyTextMessages(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            return List.of();
        }
        WorkspaceBybitIdentity identity = resolveIdentity(workspace);
        return getRawMessages(workspace, withdrawal).stream()
                .filter(message -> !message.hidden())
                .filter(message -> formatter.contentType(message) == ChatMessageContentType.TEXT)
                .filter(message -> formatter.senderType(message, identity, Set.of()) == ChatMessageSenderType.COUNTERPARTY)
                .toList();
    }

    @Scheduled(fixedDelayString = "30s")
    public void cleanChatCache() {
        Instant now = Instant.now(clock);
        Duration maxIdle = businessProperties.getChatReadCacheMaxIdle();
        if (maxIdle.isZero() || maxIdle.isNegative()) {
            chatCache.clear();
            return;
        }
        chatCache.entrySet().removeIf(
                entry -> Duration.between(entry.getValue().lastAccessAt, now).compareTo(maxIdle) > 0
        );

        int maxEntries = businessProperties.getChatReadCacheMaxEntries();
        if (maxEntries <= 0) {
            chatCache.clear();
            return;
        }
        int extraEntries = chatCache.size() - maxEntries;
        if (extraEntries <= 0) {
            return;
        }
        chatCache.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().lastAccessAt))
                .limit(extraEntries)
                .map(java.util.Map.Entry::getKey)
                .forEach(chatCache::remove);
    }

    private List<BybitChatMessage> getRawMessages(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        try {
            return cachedRemoteMessages(workspace, withdrawal);
        } catch (RuntimeException exception) {
            log.warn(
                    "Bybit chat history fetch failed: withdrawalId={}, orderId={}, message={}",
                    withdrawal.getId(),
                    withdrawal.getBybitOrderId(),
                    exception.getMessage()
            );
            throw BusinessException.serviceUnavailable("Bybit chat history is temporarily unavailable");
        }
    }

    private List<BybitChatMessage> cachedRemoteMessages(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        ChatCacheKey key = new ChatCacheKey(workspace == null ? null : workspace.getId(), withdrawal.getBybitOrderId());
        ChatCacheEntry entry = chatCache.computeIfAbsent(key, ignored -> new ChatCacheEntry());
        Instant now = Instant.now(clock);
        List<BybitChatMessage> cachedMessages = entry.messages;
        if (cachedMessages != null && fresh(entry, now)) {
            entry.lastAccessAt = now;
            return cachedMessages;
        }

        entry.lock.lock();
        try {
            now = Instant.now(clock);
            cachedMessages = entry.messages;
            if (cachedMessages != null && fresh(entry, now)) {
                entry.lastAccessAt = now;
                return cachedMessages;
            }

            List<BybitChatMessage> remoteMessages = fetchRemoteMessages(workspace, withdrawal);
            List<BybitChatMessage> immutableMessages = List.copyOf(remoteMessages);
            entry.messages = immutableMessages;
            entry.fetchedAt = now;
            entry.lastAccessAt = now;
            return immutableMessages;
        } finally {
            entry.lock.unlock();
        }
    }

    private boolean fresh(ChatCacheEntry entry, Instant now) {
        return entry.fetchedAt != null
                && Duration.between(entry.fetchedAt, now).compareTo(businessProperties.getChatReadCacheTtl()) < 0;
    }

    private List<BybitChatMessage> fetchRemoteMessages(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        if (workspace == null || workspaceSecretService == null) {
            return bybitGateway.fetchChatMessages(withdrawal.getBybitOrderId());
        }
        return bybitCredentialsContext.callWith(
                workspaceSecretService.bybitCredentials(workspace),
                () -> bybitGateway.fetchChatMessages(withdrawal.getBybitOrderId())
        );
    }

    private void storeBotMessage(
            WorkspaceEntity workspace,
            WithdrawalRequestEntity withdrawal,
            SentChatMessage sentMessage
    ) {
        if (botMessageRepository == null || !StringUtils.hasText(sentMessage.messageUuid())) {
            return;
        }
        BybitBotChatMessageEntity entity = new BybitBotChatMessageEntity();
        entity.setWorkspace(workspace);
        entity.setWithdrawalRequest(withdrawal);
        entity.setBybitOrderId(withdrawal.getBybitOrderId());
        entity.setMsgUuid(sentMessage.messageUuid());
        entity.setMessageText(sentMessage.messageText());
        entity.setCreatedAt(Instant.now(clock));
        botMessageRepository.save(entity);
    }

    private void appendSentMessageToCache(
            WorkspaceEntity workspace,
            WithdrawalRequestEntity withdrawal,
            SentChatMessage sentMessage
    ) {
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            return;
        }
        ChatCacheEntry entry = chatCache.get(new ChatCacheKey(
                workspace == null ? null : workspace.getId(),
                withdrawal.getBybitOrderId()
        ));
        if (entry == null) {
            return;
        }

        entry.lock.lock();
        try {
            List<BybitChatMessage> currentMessages = entry.messages;
            if (currentMessages == null) {
                return;
            }
            boolean alreadyCached = currentMessages.stream()
                    .anyMatch(message -> sentMessage.messageUuid().equals(message.messageUuid()));
            if (alreadyCached) {
                return;
            }

            Instant now = Instant.now(clock);
            List<BybitChatMessage> updatedMessages = new ArrayList<>(currentMessages);
            updatedMessages.add(new BybitChatMessage(
                    "local:" + sentMessage.messageUuid(),
                    sentMessage.messageText(),
                    workspace == null ? null : workspace.getBybitUserId(),
                    1,
                    now,
                    TEXT_CONTENT_TYPE,
                    withdrawal.getBybitOrderId(),
                    sentMessage.messageUuid(),
                    workspace == null ? null : workspace.getBybitNickname(),
                    "user",
                    workspace == null ? null : workspace.getBybitAccountId(),
                    0,
                    ""
            ));
            entry.messages = List.copyOf(updatedMessages);
            entry.fetchedAt = now;
            entry.lastAccessAt = now;
        } finally {
            entry.lock.unlock();
        }
    }

    private SentChatMessage sendBybitMessage(WithdrawalRequestEntity withdrawal, String messageText) {
        String messageUuid = UUID.randomUUID().toString();
        try {
            bybitGateway.sendChatMessage(withdrawal.getBybitOrderId(), messageUuid, messageText);
            return new SentChatMessage(messageUuid, messageText);
        } catch (Exception exception) {
            log.warn(
                    "Bybit chat message failed: orderId={}, withdrawalId={}, messageUuid={}, message={}",
                    withdrawal.getBybitOrderId(),
                    withdrawal.getId(),
                    messageUuid,
                    exception.getMessage()
            );
            return null;
        }
    }

    private Set<String> botMessageUuids(
            WorkspaceEntity workspace,
            String bybitOrderId,
            List<BybitChatMessage> messages
    ) {
        if (botMessageRepository == null) {
            return Set.of();
        }
        Set<String> messageUuids = messages.stream()
                .map(BybitChatMessage::messageUuid)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (messageUuids.isEmpty()) {
            return Set.of();
        }
        List<BybitBotChatMessageEntity> botMessages = workspace == null
                ? botMessageRepository.findAllByBybitOrderIdAndMsgUuidIn(bybitOrderId, messageUuids)
                : botMessageRepository.findAllByWorkspaceAndBybitOrderIdAndMsgUuidIn(
                        workspace,
                        bybitOrderId,
                        messageUuids
                );
        return botMessages.stream()
                .map(BybitBotChatMessageEntity::getMsgUuid)
                .collect(Collectors.toUnmodifiableSet());
    }

    private WorkspaceBybitIdentity resolveIdentity(WorkspaceEntity workspace) {
        if (workspace == null) {
            return new WorkspaceBybitIdentity(null, null, null);
        }
        if (workspaceBybitIdentityService != null) {
            try {
                return workspaceBybitIdentityService.resolve(workspace);
            } catch (RuntimeException exception) {
                log.warn(
                        "Bybit workspace identity fetch failed: workspace={}, message={}",
                        workspace.getPublicId(),
                        exception.getMessage()
                );
            }
        }
        return new WorkspaceBybitIdentity(
                workspace.getBybitUserId(),
                workspace.getBybitAccountId(),
                workspace.getBybitNickname()
        );
    }

    private WorkspaceEntity effectiveWorkspace(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        return workspace == null ? withdrawal.getWorkspace() : workspace;
    }

    private String normalizedMessage(String rawMessage) {
        if (!StringUtils.hasText(rawMessage)) {
            throw BusinessException.conflict("Chat message must not be blank");
        }
        return rawMessage.trim();
    }

    private void sleepBetweenMessages() {
        long delayMillis = businessProperties.getChatMessageDelay().toMillis();
        if (delayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Bybit chat message delay interrupted");
        }
    }

    private record ChatCacheKey(Long workspaceId, String bybitOrderId) {
    }

    private static final class ChatCacheEntry {

        private final ReentrantLock lock = new ReentrantLock();
        private volatile List<BybitChatMessage> messages;
        private volatile Instant fetchedAt;
        private volatile Instant lastAccessAt = Instant.EPOCH;
    }

    private record SentChatMessage(String messageUuid, String messageText) {
    }
}
