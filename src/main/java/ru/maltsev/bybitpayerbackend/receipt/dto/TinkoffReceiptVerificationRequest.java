package ru.maltsev.bybitpayerbackend.receipt.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;

public record TinkoffReceiptVerificationRequest(
        @NotNull @Positive BigDecimal amount,
        String recipient,
        String phone,
        String bank,
        WithdrawalMethod withdrawalMethod,
        String cardNumber,
        Boolean recipientCardTbank
) {

    public TinkoffReceiptVerificationRequest {
        withdrawalMethod = WithdrawalMethod.effective(withdrawalMethod);
        recipientCardTbank = Boolean.TRUE.equals(recipientCardTbank);
    }

    public TinkoffReceiptVerificationRequest(
            BigDecimal amount,
            String recipient,
            String phone,
            String bank
    ) {
        this(amount, recipient, phone, bank, WithdrawalMethod.SBP, null, false);
    }
}
