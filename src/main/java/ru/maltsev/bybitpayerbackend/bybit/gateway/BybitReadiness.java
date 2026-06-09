package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.math.BigDecimal;

public record BybitReadiness(
        boolean available,
        String mode,
        String message,
        BigDecimal availableUsdtBalance
) {
}
