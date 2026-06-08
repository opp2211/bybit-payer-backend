package ru.maltsev.bybitpayerbackend.bybit.dto;

import java.time.Instant;

public record ChatMessageLogResponse(
        Long id,
        String bybitOrderId,
        int messageIndex,
        String messageText,
        String status,
        Instant sentAt,
        String error
) {
}
