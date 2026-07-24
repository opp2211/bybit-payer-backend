package ru.maltsev.bybitpayerbackend.bybit.dto;

import java.time.Instant;

public record ChatMessageLogResponse(
        String id,
        String bybitOrderId,
        String messageUuid,
        ChatMessageSenderType senderType,
        String authorName,
        ChatMessageContentResponse content,
        ChatMessageRawResponse raw,
        String status,
        Instant createdAt,
        String error
) {
}
