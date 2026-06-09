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
        BigDecimal currentMinRub,
        BigDecimal currentMaxRub,
        BigDecimal currentQuantityUsdt,
        String currentDescription,
        BigDecimal availableUsdtBalance,
        String lastSystemError,
        Instant bybitLastCheckedAt,
        Instant lastUpdatedAt
) {
}
