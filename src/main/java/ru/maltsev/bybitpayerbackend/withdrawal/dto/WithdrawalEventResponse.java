package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import java.time.Instant;

public record WithdrawalEventResponse(
        Long id,
        String eventType,
        String message,
        String payloadJson,
        Instant createdAt
) {
}
