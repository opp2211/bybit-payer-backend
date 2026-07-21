package ru.maltsev.bybitpayerbackend.receipt.dto;

import java.math.BigDecimal;

public record TinkoffReceiptData(
        BigDecimal amount,
        String status,
        String recipient,
        String phone,
        String bank,
        String card
) {
}
