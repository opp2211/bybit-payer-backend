package ru.maltsev.bybitpayerbackend.bybit.service;

import java.math.BigDecimal;

public record AdvertisementPreview(
        BigDecimal rate,
        BigDecimal minRub,
        BigDecimal maxRub,
        BigDecimal quantityUsdt,
        String description
) {
}
