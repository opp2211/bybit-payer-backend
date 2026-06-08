package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.math.BigDecimal;

public record BybitP2pOrder(
        String bybitOrderId,
        BigDecimal amountRub,
        String status,
        boolean paid
) {
}
