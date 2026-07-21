package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;

public record CreateWithdrawalRequest(
        @NotNull @Positive BigDecimal amountRub,
        @NotBlank String recipientPhone,
        @NotBlank String recipientBank,
        @NotBlank String recipientName,
        @NotNull PayerBankType payerBankType
) {
}
