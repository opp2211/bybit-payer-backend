package ru.maltsev.bybitpayerbackend.bybit.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record FakeBybitOrderResponse(
        String bybitOrderId,
        String bybitAdId,
        BigDecimal amountRub,
        String status,
        String statusTitle,
        BigDecimal quantityUsdt,
        BigDecimal feeUsdt,
        Instant createdAt,
        Instant updatedAt,
        List<FakeBybitChatMessageResponse> chatMessages
) {
}
