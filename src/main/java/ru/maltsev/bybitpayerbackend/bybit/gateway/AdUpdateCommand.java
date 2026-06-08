package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.math.BigDecimal;

public record AdUpdateCommand(
        String bybitAdId,
        boolean published,
        BigDecimal rate,
        BigDecimal minRub,
        BigDecimal maxRub,
        BigDecimal quantityUsdt,
        String description
) {
}
