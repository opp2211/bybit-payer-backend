package ru.maltsev.bybitpayerbackend.bybit.dto;

import java.time.Instant;

public record ChatMessageLogResponse(
        String id,
        String bybitOrderId,
        Integer messageIndex,
        String messageText,
        String direction,
        String authorName,
        String contentType,
        String status,
        Instant createdAt,
        String error
) {
}
