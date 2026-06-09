package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record WithdrawalResponse(
        Long id,
        BigDecimal amountRub,
        String recipientPhone,
        String recipientBank,
        String recipientBankTitle,
        String recipientName,
        String status,
        String statusTitle,
        boolean attentionRequired,
        boolean completionSeen,
        String queueGroupKey,
        Integer queuePosition,
        String bybitOrderId,
        BigDecimal bybitOrderAmountRub,
        BigDecimal bybitOrderQuantityUsdt,
        BigDecimal bybitOrderFeeUsdt,
        BigDecimal bybitOrderTotalUsdt,
        Instant createdAt,
        Instant queuedAt,
        Instant publishedAt,
        Instant orderFoundAt,
        Instant requisitesSentAt,
        Instant paidAt,
        Instant verificationStartedAt,
        Instant completedAt,
        Instant cancelledAt,
        String lastError,
        String lastWarning,
        boolean canCancel,
        boolean canRelease
) {
}
