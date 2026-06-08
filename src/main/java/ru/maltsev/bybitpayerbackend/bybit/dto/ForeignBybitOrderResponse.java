package ru.maltsev.bybitpayerbackend.bybit.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ForeignBybitOrderResponse(
        Long id,
        String bybitOrderId,
        BigDecimal amountRub,
        String bybitStatus,
        String reason,
        boolean cancelRequested,
        int cancelRequestAttempts,
        Instant cancelRequestedAt,
        boolean attentionRequired,
        Instant createdAt,
        Instant updatedAt,
        String lastError
) {
}
