package ru.maltsev.bybitpayerbackend.receipt.dto;

import java.util.List;

public record TinkoffReceiptValidationResult(
        boolean valid,
        TinkoffReceiptData receipt,
        List<String> errors
) {
}
