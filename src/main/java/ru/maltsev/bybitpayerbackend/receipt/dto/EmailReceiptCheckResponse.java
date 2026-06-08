package ru.maltsev.bybitpayerbackend.receipt.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record EmailReceiptCheckResponse(
        Long id,
        String bybitOrderId,
        String emailMessageId,
        String emailFrom,
        String emailSubject,
        Instant emailReceivedAt,
        String pdfFilename,
        String parsedStatus,
        BigDecimal parsedAmountRub,
        String parsedRecipientPhone,
        String parsedRecipientBank,
        String parsedRecipientName,
        String parsedOperationDate,
        String parsedOperationId,
        String parsedReceiptNumber,
        String verificationStatus,
        String verificationError,
        Instant createdAt
) {
}
