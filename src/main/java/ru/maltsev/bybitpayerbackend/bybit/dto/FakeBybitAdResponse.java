package ru.maltsev.bybitpayerbackend.bybit.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record FakeBybitAdResponse(
        String bybitAdId,
        String workspacePublicId,
        String workspaceName,
        boolean published,
        BigDecimal rate,
        BigDecimal minRub,
        BigDecimal maxRub,
        BigDecimal quantityUsdt,
        String description,
        int activeOrderCount,
        Instant updatedAt
) {
}
