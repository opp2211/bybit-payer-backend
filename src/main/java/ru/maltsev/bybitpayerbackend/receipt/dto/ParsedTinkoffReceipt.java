package ru.maltsev.bybitpayerbackend.receipt.dto;

public record ParsedTinkoffReceipt(
        TinkoffReceiptData data,
        String rawText
) {
}
