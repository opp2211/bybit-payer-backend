package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitChatMessageLogEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.model.ChatMessageStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitChatMessageLogRepository;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

@Service
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
        } else {
            withdrawal.setAttentionRequired(true);
            withdrawal.setLastWarning("Not all chat messages were sent");
            eventService.add(withdrawal, WithdrawalEventType.ATTENTION_REQUIRED, "Not all chat messages were sent");
        }
        withdrawalRepository.save(withdrawal);
    }

    private boolean sendMessageIfNeeded(WithdrawalRequestEntity withdrawal, int messageIndex, String messageText) {
        return chatMessageLogRepository
                .findByBybitOrderIdAndMessageIndex(withdrawal.getBybitOrderId(), messageIndex)
                .map(existing -> existing.getStatus() == ChatMessageStatus.SENT)
                .orElseGet(() -> createAndSendMessage(withdrawal, messageIndex, messageText));
    }

    private boolean createAndSendMessage(WithdrawalRequestEntity withdrawal, int messageIndex, String messageText) {
        BybitChatMessageLogEntity log = new BybitChatMessageLogEntity();
        log.setBybitOrderId(withdrawal.getBybitOrderId());
        log.setWithdrawalRequest(withdrawal);
        log.setMessageIndex(messageIndex);
        log.setMessageText(messageText);
        log.setStatus(ChatMessageStatus.PENDING);
        try {
            log = chatMessageLogRepository.saveAndFlush(log);
            bybitGateway.sendChatMessage(withdrawal.getBybitOrderId(), messageIndex, messageText);
            log.setStatus(ChatMessageStatus.SENT);
            log.setSentAt(Instant.now(clock));
            chatMessageLogRepository.save(log);
            return true;
        } catch (DataIntegrityViolationException exception) {
            return chatMessageLogRepository
                    .findByBybitOrderIdAndMessageIndex(withdrawal.getBybitOrderId(), messageIndex)
                    .map(existing -> existing.getStatus() == ChatMessageStatus.SENT)
                    .orElse(false);
        } catch (Exception exception) {
            log.setStatus(ChatMessageStatus.FAILED);
            log.setError(exception.getMessage());
            chatMessageLogRepository.save(log);
            return false;
        }
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
        }
    }
}
