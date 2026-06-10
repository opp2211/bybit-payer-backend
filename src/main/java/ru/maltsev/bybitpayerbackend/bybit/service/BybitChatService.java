package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitChatMessageLogEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitChatMessage;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.model.ChatMessageStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitChatMessageLogRepository;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

@Service
@Slf4j
public class BybitChatService {

    private static final String HELLO_MESSAGE = "\u041f\u0440\u0438\u0432\u0435\u0442";

    private final BybitChatMessageLogRepository chatMessageLogRepository;
    private final WithdrawalRequestRepository withdrawalRepository;
    private final WithdrawalEventService eventService;
    private final BybitGateway bybitGateway;
    private final BusinessProperties businessProperties;
    private final Clock clock;

    public BybitChatService(
            BybitChatMessageLogRepository chatMessageLogRepository,
            WithdrawalRequestRepository withdrawalRepository,
            WithdrawalEventService eventService,
            BybitGateway bybitGateway,
            BusinessProperties businessProperties,
            Clock clock
    ) {
        this.chatMessageLogRepository = chatMessageLogRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.eventService = eventService;
        this.bybitGateway = bybitGateway;
        this.businessProperties = businessProperties;
        this.clock = clock;
    }

    @Transactional
    public void sendRequisites(WithdrawalRequestEntity withdrawal) {
        List<String> messages = List.of(
                HELLO_MESSAGE,
                withdrawal.getRecipientPhone(),
                withdrawal.getRecipientBank().getTitle() + ", " + withdrawal.getRecipientName(),
                businessProperties.getReceiptEmailToSendInChat()
        );

        boolean allSent = true;
        for (int index = 0; index < messages.size(); index++) {
            boolean sent = sendMessageIfNeeded(withdrawal, index + 1, messages.get(index));
            allSent = allSent && sent;
            sleepBetweenMessages();
        }

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

    @Transactional
    public void sendMessage(Long withdrawalId, String rawMessage) {
        WithdrawalRequestEntity withdrawal = withdrawalRepository.findById(withdrawalId)
                .orElseThrow(() -> BusinessException.conflict("Заявка не найдена"));
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            throw BusinessException.conflict("К заявке ещё не привязан Bybit-ордер");
        }

        String message = rawMessage.trim();
        int messageIndex = chatMessageLogRepository
                .findTopByBybitOrderIdOrderByMessageIndexDesc(withdrawal.getBybitOrderId())
                .map(existing -> existing.getMessageIndex() + 1)
                .orElse(1);
        if (!createAndSendMessage(withdrawal, messageIndex, message)) {
            throw BusinessException.conflict("Не удалось отправить сообщение в чат Bybit");
        }
        eventService.add(withdrawal, WithdrawalEventType.CHAT_MESSAGE_SENT, "Chat message sent by operator");
    }

    @Transactional(readOnly = true)
    public List<ChatMessageLogResponse> getMessages(WithdrawalRequestEntity withdrawal) {
        List<BybitChatMessageLogEntity> localMessages =
                chatMessageLogRepository.findByWithdrawalRequest_IdOrderByMessageIndexAsc(withdrawal.getId());
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            return localMessages.stream().map(this::toLocalResponse).toList();
        }

        List<BybitChatMessage> remoteMessages;
        try {
            remoteMessages = bybitGateway.fetchChatMessages(withdrawal.getBybitOrderId());
        } catch (RuntimeException exception) {
            log.debug(
                    "Bybit chat history is temporarily unavailable: withdrawalId={}, orderId={}, message={}",
                    withdrawal.getId(),
                    withdrawal.getBybitOrderId(),
                    exception.getMessage()
            );
            return localMessages.stream().map(this::toLocalResponse).toList();
        }

        Set<Long> matchedLocalIds = new HashSet<>();
        List<ChatMessageLogResponse> result = new ArrayList<>();
        for (BybitChatMessage remote : remoteMessages) {
            BybitChatMessageLogEntity local = findLocalMessage(remote, localMessages, matchedLocalIds);
            if (local != null) {
                matchedLocalIds.add(local.getId());
            }
            String direction = remote.system() ? "SYSTEM" : local == null ? "INCOMING" : "OUTGOING";
            String author = switch (direction) {
                case "SYSTEM" -> "Bybit";
                case "OUTGOING" -> "Вы";
                default -> StringUtils.hasText(remote.nickname()) ? remote.nickname() : "Контрагент";
            };
            result.add(new ChatMessageLogResponse(
                    "bybit:" + remote.id(),
                    remote.orderId(),
                    local == null ? null : local.getMessageIndex(),
                    remote.message(),
                    direction,
                    author,
                    remote.contentType(),
                    "SENT",
                    remote.createdAt(),
                    null
            ));
        }
        localMessages.stream()
                .filter(local -> !matchedLocalIds.contains(local.getId()))
                .map(this::toLocalResponse)
                .forEach(result::add);
        result.sort(Comparator.comparing(
                ChatMessageLogResponse::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())
        ));
        return List.copyOf(result);
    }

    private boolean sendMessageIfNeeded(WithdrawalRequestEntity withdrawal, int messageIndex, String messageText) {
        return chatMessageLogRepository
                .findByBybitOrderIdAndMessageIndex(withdrawal.getBybitOrderId(), messageIndex)
                .map(existing -> existing.getStatus() == ChatMessageStatus.SENT)
                .orElseGet(() -> createAndSendMessage(withdrawal, messageIndex, messageText));
    }

    private boolean createAndSendMessage(WithdrawalRequestEntity withdrawal, int messageIndex, String messageText) {
        BybitChatMessageLogEntity messageLog = new BybitChatMessageLogEntity();
        messageLog.setBybitOrderId(withdrawal.getBybitOrderId());
        messageLog.setWithdrawalRequest(withdrawal);
        messageLog.setMessageIndex(messageIndex);
        messageLog.setMessageText(messageText);
        messageLog.setClientMessageId(clientMessageId(withdrawal.getBybitOrderId(), messageIndex));
        messageLog.setStatus(ChatMessageStatus.PENDING);
        try {
            messageLog = chatMessageLogRepository.saveAndFlush(messageLog);
            bybitGateway.sendChatMessage(
                    withdrawal.getBybitOrderId(),
                    messageLog.getClientMessageId(),
                    messageText
            );
            messageLog.setStatus(ChatMessageStatus.SENT);
            messageLog.setSentAt(Instant.now(clock));
            chatMessageLogRepository.save(messageLog);
            return true;
        } catch (DataIntegrityViolationException exception) {
            log.debug(
                    "Bybit chat message already registered: orderId={}, messageIndex={}",
                    withdrawal.getBybitOrderId(),
                    messageIndex
            );
            return chatMessageLogRepository
                    .findByBybitOrderIdAndMessageIndex(withdrawal.getBybitOrderId(), messageIndex)
                    .map(existing -> existing.getStatus() == ChatMessageStatus.SENT)
                    .orElse(false);
        } catch (Exception exception) {
            messageLog.setStatus(ChatMessageStatus.FAILED);
            messageLog.setError(exception.getMessage());
            chatMessageLogRepository.save(messageLog);
            log.warn(
                    "Bybit chat message failed: orderId={}, withdrawalId={}, messageIndex={}, message={}",
                    withdrawal.getBybitOrderId(),
                    withdrawal.getId(),
                    messageIndex,
                    exception.getMessage()
            );
            return false;
        }
    }

    private BybitChatMessageLogEntity findLocalMessage(
            BybitChatMessage remote,
            List<BybitChatMessageLogEntity> localMessages,
            Set<Long> matchedLocalIds
    ) {
        for (BybitChatMessageLogEntity local : localMessages) {
            if (matchedLocalIds.contains(local.getId())) {
                continue;
            }
            String expectedMessageId = StringUtils.hasText(local.getClientMessageId())
                    ? local.getClientMessageId()
                    : clientMessageId(local.getBybitOrderId(), local.getMessageIndex());
            if (expectedMessageId.equals(remote.messageUuid())) {
                return local;
            }
        }
        if (!StringUtils.hasText(remote.messageUuid())) {
            for (BybitChatMessageLogEntity local : localMessages) {
                if (!matchedLocalIds.contains(local.getId())
                        && local.getStatus() == ChatMessageStatus.SENT
                        && local.getMessageText().equals(remote.message())) {
                    return local;
                }
            }
        }
        return null;
    }

    private ChatMessageLogResponse toLocalResponse(BybitChatMessageLogEntity message) {
        return new ChatMessageLogResponse(
                "local:" + message.getId(),
                message.getBybitOrderId(),
                message.getMessageIndex(),
                message.getMessageText(),
                "OUTGOING",
                "Вы",
                "str",
                message.getStatus().name(),
                message.getSentAt(),
                message.getError()
        );
    }

    private String clientMessageId(String bybitOrderId, int messageIndex) {
        return UUID.nameUUIDFromBytes((bybitOrderId + ":" + messageIndex).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
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
}
