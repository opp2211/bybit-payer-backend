package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.audit.service.AuditService;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitChatMessage;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceAccessService;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

@Service
@Slf4j
public class BybitChatService {

    private static final String HELLO_MESSAGE = "\u041f\u0440\u0438\u0432\u0435\u0442";

    private final WithdrawalRequestRepository withdrawalRepository;
    private final WithdrawalEventService eventService;
    private final BybitGateway bybitGateway;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final WorkspaceSecretService workspaceSecretService;
    private final WorkspaceAccessService workspaceAccessService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final BusinessProperties businessProperties;
    private final Clock clock;

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
                businessProperties,
                clock
        );
    }

    @Transactional
    public void sendRequisites(WithdrawalRequestEntity withdrawal) {
        List<String> messages = new ArrayList<>(List.of(
                HELLO_MESSAGE,
                withdrawal.getRecipientPhone(),
                withdrawal.getRecipientBank().getTitle() + ", " + withdrawal.getRecipientName()
        ));
        if (PayerBankType.effective(withdrawal.getPayerBankType()).isAutoReleaseEnabled()) {
            messages.add(businessProperties.getReceiptEmailToSendInChat());
        }

        boolean allSent = true;
        for (String message : messages) {
            boolean sent = sendBybitMessage(withdrawal, message);
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
                .orElseThrow(() -> BusinessException.conflict("Withdrawal not found"));
        if (!StringUtils.hasText(withdrawal.getBybitOrderId())) {
            throw BusinessException.conflict("Bybit order is not linked to withdrawal yet");
        }

        String message = rawMessage.trim();
        if (!sendBybitMessage(withdrawal, message)) {
            throw BusinessException.conflict("Failed to send message to Bybit chat");
        }
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

        String message = rawMessage.trim();
        boolean sent = bybitCredentialsContext.callWith(
                workspaceSecretService.bybitCredentials(workspace),
                () -> sendBybitMessage(withdrawal, message)
        );
        if (!sent) {
            throw BusinessException.conflict("Failed to send message to Bybit chat");
        }
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

        List<BybitChatMessage> remoteMessages;
        try {
            if (workspace == null || workspaceSecretService == null) {
                remoteMessages = bybitGateway.fetchChatMessages(withdrawal.getBybitOrderId());
            } else {
                remoteMessages = bybitCredentialsContext.callWith(
                        workspaceSecretService.bybitCredentials(workspace),
                        () -> bybitGateway.fetchChatMessages(withdrawal.getBybitOrderId())
                );
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "Bybit chat history fetch failed: withdrawalId={}, orderId={}, message={}",
                    withdrawal.getId(),
                    withdrawal.getBybitOrderId(),
                    exception.getMessage()
            );
            throw BusinessException.serviceUnavailable("Bybit chat history is temporarily unavailable");
        }

        List<ChatMessageLogResponse> result = new ArrayList<>();
        for (BybitChatMessage remote : remoteMessages) {
            result.add(toRemoteResponse(remote));
        }
        result.sort(Comparator.comparing(
                ChatMessageLogResponse::createdAt,
                Comparator.nullsLast(Comparator.naturalOrder())
        ));
        return List.copyOf(result);
    }

    private boolean sendBybitMessage(WithdrawalRequestEntity withdrawal, String messageText) {
        String messageUuid = UUID.randomUUID().toString();
        try {
            bybitGateway.sendChatMessage(withdrawal.getBybitOrderId(), messageUuid, messageText);
            return true;
        } catch (Exception exception) {
            log.warn(
                    "Bybit chat message failed: orderId={}, withdrawalId={}, messageUuid={}, message={}",
                    withdrawal.getBybitOrderId(),
                    withdrawal.getId(),
                    messageUuid,
                    exception.getMessage()
            );
            return false;
        }
    }

    private ChatMessageLogResponse toRemoteResponse(BybitChatMessage message) {
        String direction = direction(message);
        return new ChatMessageLogResponse(
                "bybit:" + message.id(),
                message.orderId(),
                null,
                message.message(),
                direction,
                authorName(message, direction),
                message.contentType(),
                "SENT",
                message.createdAt(),
                null
        );
    }

    private String direction(BybitChatMessage message) {
        if (message.system()) {
            return "SYSTEM";
        }
        return "user".equalsIgnoreCase(message.roleType()) ? "INCOMING" : "OUTGOING";
    }

    private String authorName(BybitChatMessage message, String direction) {
        return switch (direction) {
            case "SYSTEM" -> "Bybit";
            case "OUTGOING" -> "\u0412\u044b";
            default -> StringUtils.hasText(message.nickname()) ? message.nickname() : "\u041a\u043e\u043d\u0442\u0440\u0430\u0433\u0435\u043d\u0442";
        };
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
