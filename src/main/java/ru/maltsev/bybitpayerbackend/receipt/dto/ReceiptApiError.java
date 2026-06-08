package ru.maltsev.bybitpayerbackend.receipt.dto;

import java.util.List;

public record ReceiptApiError(
        String message,
        List<String> details
) {

    public ReceiptApiError(String message) {
        this(message, List.of());
    }
}
