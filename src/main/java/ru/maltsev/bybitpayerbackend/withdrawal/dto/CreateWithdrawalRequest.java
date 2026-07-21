package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;

public record CreateWithdrawalRequest(
        @NotNull @Positive BigDecimal amountRub,
        String recipientPhone,
        String recipientBank,
        String recipientName,
        String recipientCardNumber,
        String recipientAccountNumber,
        @NotNull Boolean recipientCardTbank,
        @NotNull Boolean thirdPartyTransfer,
        @NotNull PayerBankType payerBankType,
        @NotNull WithdrawalMethod withdrawalMethod
) {
}
