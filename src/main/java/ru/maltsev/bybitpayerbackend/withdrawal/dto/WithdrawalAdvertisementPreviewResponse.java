package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import java.math.BigDecimal;

public record WithdrawalAdvertisementPreviewResponse(
        BigDecimal rate,
        BigDecimal minRub,
        BigDecimal maxRub,
        BigDecimal amountMinRub,
        BigDecimal amountMaxRub,
        BigDecimal quantityUsdt,
        String description
) {
}
