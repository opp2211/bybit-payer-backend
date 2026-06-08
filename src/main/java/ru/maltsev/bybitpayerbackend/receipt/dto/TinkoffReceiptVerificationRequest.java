package ru.maltsev.bybitpayerbackend.receipt.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.util.StringUtils;

public record TinkoffReceiptVerificationRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String recipient,
        @NotBlank String phone,
        @NotBlank String bank
) {

    private static final String DEFAULT_SUCCESS_STATUS = "Успешно";
}
