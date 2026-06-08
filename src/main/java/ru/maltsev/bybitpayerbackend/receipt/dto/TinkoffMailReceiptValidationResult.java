package ru.maltsev.bybitpayerbackend.receipt.dto;

import java.time.Instant;
import java.util.List;

public record TinkoffMailReceiptValidationResult(
        boolean valid,
        String messageId,
        String subject,
        String from,
        Instant receivedAt,
        String attachmentName,
        TinkoffReceiptData receipt,
        List<String> errors
) {
}
