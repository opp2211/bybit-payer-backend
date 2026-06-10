package ru.maltsev.bybitpayerbackend.bybit.service;

import java.math.BigDecimal;

public record AdvertisementSnapshot(
        boolean published,
        BigDecimal rate,
        int rateSourcePosition,
        BigDecimal referenceRate7,
        BigDecimal referenceRate7WithFee,
        BigDecimal referenceRate15,
        BigDecimal minRub,
        BigDecimal maxRub,
        BigDecimal quantityUsdt,
        String description,
        BigDecimal availableUsdt
) {
}
