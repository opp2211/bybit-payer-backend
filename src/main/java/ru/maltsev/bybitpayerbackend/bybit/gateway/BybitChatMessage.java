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
        String roleType
) {
    public boolean system() {
        return messageType == 0 || "sys".equalsIgnoreCase(roleType);
    }
}
