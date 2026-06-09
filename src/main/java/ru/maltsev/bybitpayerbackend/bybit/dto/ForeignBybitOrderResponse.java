package ru.maltsev.bybitpayerbackend.bybit.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ForeignBybitOrderResponse(
        Long id,
        String bybitOrderId,
        BigDecimal amountRub,
        String bybitStatus,
        String reason,
        Instant createdAt,
        Instant updatedAt
) {
}
