package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.math.BigDecimal;

public record BybitP2pOrder(
        String bybitOrderId,
        BigDecimal amountRub,
        String status,
        BigDecimal quantityUsdt,
        BigDecimal feeUsdt
) {

    private static final String WAITING_SELLER_RELEASE = "20";
    private static final String CANCELLED = "40";
    private static final String FINISHED = "50";
    private static final String PAYMENT_FAILED = "70";
    private static final String EXCEPTION_CANCELLED = "80";

    public boolean paid() {
        return WAITING_SELLER_RELEASE.equals(status);
    }

    public boolean cancelled() {
        return CANCELLED.equals(status)
                || PAYMENT_FAILED.equals(status)
                || EXCEPTION_CANCELLED.equals(status);
    }

    public boolean finished() {
        return FINISHED.equals(status);
    }

    public BigDecimal totalUsdt() {
        BigDecimal quantity = quantityUsdt == null ? BigDecimal.ZERO : quantityUsdt;
        BigDecimal fee = feeUsdt == null ? BigDecimal.ZERO : feeUsdt;
        return quantity.add(fee);
    }
}
