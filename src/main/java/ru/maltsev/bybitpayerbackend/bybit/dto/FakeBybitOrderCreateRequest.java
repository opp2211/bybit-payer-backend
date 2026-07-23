package ru.maltsev.bybitpayerbackend.bybit.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FakeBybitOrderCreateRequest(
        @NotNull
        @Positive
        BigDecimal amountRub
) {
}
