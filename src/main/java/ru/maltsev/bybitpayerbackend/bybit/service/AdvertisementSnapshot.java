package ru.maltsev.bybitpayerbackend.bybit.service;

import java.math.BigDecimal;

public record AdvertisementSnapshot(
        boolean published,
        BigDecimal rate,
        BigDecimal minRub,
        BigDecimal maxRub,
        BigDecimal quantityUsdt,
        String description,
        BigDecimal availableUsdt
) {
}
