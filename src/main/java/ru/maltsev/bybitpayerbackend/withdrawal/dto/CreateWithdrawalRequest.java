package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateWithdrawalRequest(
        @NotNull @Positive BigDecimal amountRub,
        @NotBlank String recipientPhone,
        @NotBlank String recipientBank,
        @NotBlank String recipientName
) {
}
