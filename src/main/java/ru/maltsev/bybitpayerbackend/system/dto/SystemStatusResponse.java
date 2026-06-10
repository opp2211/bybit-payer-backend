package ru.maltsev.bybitpayerbackend.system.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SystemStatusResponse(
        boolean bybitApiAvailable,
        String bybitMode,
        boolean gmailImapsAvailable,
        String bybitAdId,
        boolean adPublished,
        BigDecimal currentRate,
        Integer currentRateSourcePosition,
        BigDecimal referenceRate7,
        BigDecimal referenceRate7WithFee,
        BigDecimal referenceRate15,
        BigDecimal currentMinRub,
        BigDecimal currentMaxRub,
        BigDecimal currentQuantityUsdt,
        String currentDescription,
        BigDecimal availableUsdtBalance,
        BigDecimal availableRubBalance,
        String lastSystemError,
        Instant bybitLastCheckedAt,
        Instant lastUpdatedAt
) {
}
