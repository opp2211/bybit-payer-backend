package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.time.Instant;

public record BybitChatMessage(
        String id,
        String message,
        String userId,
        int messageType,
        Instant createdAt,
        String contentType,
        String orderId,
        String messageUuid,
        String nickname,
        String roleType,
        String accountId,
        Integer messageCode,
        String fileName
) {
    public BybitChatMessage(
            String id,
            String message,
            String userId,
            int messageType,
            Instant createdAt,
            String contentType,
            String orderId,
            String messageUuid,
            String nickname,
            String roleType
    ) {
        this(id, message, userId, messageType, createdAt, contentType, orderId, messageUuid, nickname, roleType, null,
                null, null);
    }

    public boolean system() {
        return !support() && (messageType == 0 || messageType == 103 || "sys".equalsIgnoreCase(roleType)
                || "alarm".equalsIgnoreCase(roleType));
    }

    public boolean support() {
        return messageType == 5 || messageType == 6;
    }

    public boolean hidden() {
        return messageType == 11 || "SYS_ORDER_CARD".equalsIgnoreCase(contentType);
    }
}
