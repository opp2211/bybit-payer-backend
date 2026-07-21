package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import java.math.BigDecimal;

public record WithdrawalAdvertisementPreviewResponse(
        BigDecimal rate,
        BigDecimal minRub,
        BigDecimal maxRub,
        BigDecimal quantityUsdt,
        String description
) {
}
