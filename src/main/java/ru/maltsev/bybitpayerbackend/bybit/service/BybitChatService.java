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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
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
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalUpdatesStream.ChatUpdate;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceAccessService;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceBybitIdentity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceBybitIdentityService;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;

@Service
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class BybitChatService {

    private static final String HELLO_MESSAGE = "\u041f\u0440\u0438\u0432\u0435\u0442";
    private static final String FIRST_PARTY_SENDER_WARNING = """
            Работаю ТОЛЬКО с первыми лицами - Имя Ф. отправителя должны совпадать с верифицированным именем на Bybit.
            Это ВАЖНО! Если вы совершите платеж от 3 лица, без согласования, я буду делать возврат отправителю, но, поверьте, это будет очень долго""";
    private static final String TEXT_CONTENT_TYPE = "str";
    private static final String THANKS_MESSAGE = "Спасибо";

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
    private final ApplicationEventPublisher publisher;
    private final ConcurrentHashMap<ChatCacheKey, ChatCacheEntry> chatCache = new ConcurrentHashMap<>();

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
                clock,
                event -> {}
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
        if (withdrawal.isRequireSenderFirstParty()) {
            messages.add(FIRST_PARTY_SENDER_WARNING);
        }

        boolean allSent = sendRequisiteMessages(workspace, withdrawal, messages);

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

    private List<String> requisiteMessages(WithdrawalRequestEntity withdrawal) {
        WithdrawalMethod withdrawalMethod = WithdrawalMethod.effective(withdrawal.getWithdrawalMethod());
        return switch (withdrawalMethod) {
            case SBP -> List.of(
                    withdrawal.getRecipientPhone(),
                    recipientDetails(withdrawal)
            );
            case CARD_NUMBER -> {
                List<String> messages = new ArrayList<>();
                messages.add(withdrawal.getRecipientCardNumber());
                String recipientDetails = recipientDetails(withdrawal);
                if (StringUtils.hasText(recipientDetails)) {
                    messages.add(recipientDetails);
                }
                yield messages;
            }
            case ACCOUNT_NUMBER -> List.of(
                    withdrawal.getRecipientAccountNumber(),
                    recipientDetails(withdrawal)
            );
        };
    }

    private String recipientDetails(WithdrawalRequestEntity withdrawal) {
        List<String> details = new ArrayList<>();
        if (withdrawal.getRecipientBank() != null
                && StringUtils.hasText(withdrawal.getRecipientBank().getTitle())) {
            details.add(withdrawal.getRecipientBank().getTitle());
        }
        if (StringUtils.hasText(withdrawal.getRecipientName())) {
            details.add(withdrawal.getRecipientName());
        }
        return String.join(", ", details);
    }

    private boolean sendRequisiteMessages(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal, List<String> messages) {
        WorkspaceEntity effectiveWorkspace = effectiveWorkspace(workspace, withdrawal);
        boolean allSent = true;
        for (String rawMessage : messages) {
            if (!StringUtils.hasText(rawMessage)) {
                continue;
            }
            String message = rawMessage.trim();
            if (!sendBotMessage(effectiveWorkspace, withdrawal, message, true)) {
                allSent = false;
            }
            sleepBetweenMessages();
        }
        return allSent;
    }

    @Transactional
    public void sendOrderThanks(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        sendBotMessage(effectiveWorkspace(workspace, withdrawal), withdrawal, THANKS_MESSAGE, false);
    }

    private boolean sendBotMessage(
            WorkspaceEntity workspace,
            WithdrawalRequestEntity withdrawal,
            String text,
            boolean retry
    ) {
        SentChatMessage sentMessage = workspace != null && workspaceSecretService != null
                ? bybitCredentialsContext.callWith(
                        workspaceSecretService.bybitCredentials(workspace),
                        () -> sendBybitMessage(withdrawal, text, retry)
                )
                : sendBybitMessage(withdrawal, text, retry);
        if (sentMessage == null) {
            return false;
        }
        storeBotMessage(workspace, withdrawal, sentMessage);
        appendSentMessageToCache(workspace, withdrawal, sentMessage);
        return true;
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
        auditService.add(currentUser, workspace, "BYBIT_CHAT_MESSAGE_SENT", "WITHDRAWAL", withdrawal.getPublicId(), null);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageLogResponse> getMessages(WithdrawalRequestEntity withdrawal) {
        return getHistory(null, withdrawal).messages();
    }

    @Transactional(readOnly = true)
    public List<ChatMessageLogResponse> getMessages(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        return getHistory(workspace, withdrawal).messages();
    }

    @Transactional(readOnly = true)
    public ChatHistory getHistory(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            return new ChatHistory(List.of(), false);
        }

        ChatSnapshot snapshot = cachedRemoteMessages(workspace, withdrawal);
        WorkspaceBybitIdentity identity = resolveIdentity(workspace);
        Set<String> botMessageUuids = botMessageUuids(workspace, withdrawal.getBybitOrderId(), snapshot.messages());

        List<ChatMessageLogResponse> messages = snapshot.messages().stream()
                .map(message -> formatter.toResponse(message, identity, botMessageUuids))
                .sorted(Comparator.comparing(
                        ChatMessageLogResponse::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ).thenComparing(ChatMessageLogResponse::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return new ChatHistory(messages, snapshot.unavailable());
    }

    @Transactional(readOnly = true)
    public List<BybitChatMessage> getCounterpartyTextMessages(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            return List.of();
        }
        WorkspaceBybitIdentity identity = resolveIdentity(workspace);
        return cachedRemoteMessages(workspace, withdrawal).messages().stream()
                .filter(message -> formatter.contentType(message) == ChatMessageContentType.TEXT)
                .filter(message -> formatter.senderType(message, identity, Set.of()) == ChatMessageSenderType.COUNTERPARTY)
                .toList();
    }

    void refreshHistory(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        if (StringUtils.hasText(withdrawal.getBybitOrderId())) {
            cachedRemoteMessages(workspace, withdrawal);
        }
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

    private ChatSnapshot cachedRemoteMessages(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        ChatCacheKey key = new ChatCacheKey(workspace == null ? null : workspace.getId(), withdrawal.getBybitOrderId());
        ChatCacheEntry entry = chatCache.computeIfAbsent(key, ignored -> new ChatCacheEntry());
        Instant now = Instant.now(clock);
        if (fresh(entry, now) || retryBlocked(entry, now)) {
            entry.lastAccessAt = now;
            return entry.snapshot;
        }

        entry.lock.lock();
        try {
            now = Instant.now(clock);
            if (fresh(entry, now) || retryBlocked(entry, now)) {
                entry.lastAccessAt = now;
                return entry.snapshot;
            }

            List<BybitChatMessage> messages;
            try {
                messages = fetchRemoteMessages(workspace, withdrawal).stream()
                        .filter(message -> !message.hidden())
                        .toList();
            } catch (RuntimeException exception) {
                Duration failureBackoff = businessProperties.getChatReadFailureBackoff();
                if (failureBackoff.isZero() || failureBackoff.isNegative()) {
                    failureBackoff = Duration.ofSeconds(1);
                }
                Instant failedAt = Instant.now(clock);
                entry.retryAfter = failedAt.plus(failureBackoff);
                entry.lastAccessAt = failedAt;
                log.warn(
                        "Bybit chat history fetch failed; serving cached data: withdrawalId={}, orderId={}, retryAfter={}, message={}",
                        withdrawal.getId(),
                        withdrawal.getBybitOrderId(),
                        entry.retryAfter,
                        exception.getMessage()
                );
                return updateSnapshot(workspace, withdrawal, entry, new ChatSnapshot(entry.snapshot.messages(), true));
            }
            entry.fetchedAt = Instant.now(clock);
            entry.lastAccessAt = entry.fetchedAt;
            entry.retryAfter = null;
            return updateSnapshot(workspace, withdrawal, entry, new ChatSnapshot(messages, false));
        } finally {
            entry.lock.unlock();
        }
    }

    private ChatSnapshot updateSnapshot(
            WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal, ChatCacheEntry entry, ChatSnapshot snapshot
    ) {
        if (!entry.snapshot.equals(snapshot)) {
            entry.snapshot = snapshot;
            publishChatUpdate(workspace, withdrawal);
        }
        return entry.snapshot;
    }

    private void publishChatUpdate(WorkspaceEntity workspace, WithdrawalRequestEntity withdrawal) {
        if (workspace != null && workspace.getId() != null && withdrawal.getPublicId() != null) {
            publisher.publishEvent(new ChatUpdate(workspace.getId(), withdrawal.getPublicId()));
        }
    }

    private boolean fresh(ChatCacheEntry entry, Instant now) {
        return entry.fetchedAt != null
                && Duration.between(entry.fetchedAt, now).compareTo(businessProperties.getChatReadCacheTtl()) < 0;
    }

    private boolean retryBlocked(ChatCacheEntry entry, Instant now) {
        return entry.retryAfter != null && now.isBefore(entry.retryAfter);
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
            publishChatUpdate(workspace, withdrawal);
            return;
        }

        entry.lock.lock();
        try {
            List<BybitChatMessage> currentMessages = entry.snapshot.messages();
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
            entry.lastAccessAt = now;
            if (!entry.snapshot.unavailable() && entry.fetchedAt != null) {
                entry.fetchedAt = now;
            }
            updateSnapshot(workspace, withdrawal, entry,
                    new ChatSnapshot(List.copyOf(updatedMessages), entry.snapshot.unavailable()));
        } finally {
            entry.lock.unlock();
        }
    }

    private SentChatMessage sendBybitMessage(WithdrawalRequestEntity withdrawal, String messageText) {
        return sendBybitMessage(withdrawal, messageText, true);
    }

    private SentChatMessage sendBybitMessage(WithdrawalRequestEntity withdrawal, String messageText, boolean retry) {
        String messageUuid = UUID.randomUUID().toString();
        try {
            if (retry) {
                bybitGateway.sendChatMessage(withdrawal.getBybitOrderId(), messageUuid, messageText);
            } else {
                bybitGateway.sendChatMessageOnce(withdrawal.getBybitOrderId(), messageUuid, messageText);
            }
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

    public record ChatHistory(List<ChatMessageLogResponse> messages, boolean unavailable) {
    }

    private record ChatSnapshot(List<BybitChatMessage> messages, boolean unavailable) {
    }

    private static final class ChatCacheEntry {

        private final ReentrantLock lock = new ReentrantLock();
        private volatile ChatSnapshot snapshot = new ChatSnapshot(List.of(), false);
        private volatile Instant fetchedAt;
        private volatile Instant lastAccessAt = Instant.EPOCH;
        private volatile Instant retryAfter;
    }

    private record SentChatMessage(String messageUuid, String messageText) {
    }
}
