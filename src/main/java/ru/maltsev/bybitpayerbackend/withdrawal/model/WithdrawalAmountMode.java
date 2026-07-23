package ru.maltsev.bybitpayerbackend.withdrawal.model;

public enum WithdrawalAmountMode {
    FIXED,
    RANGE;

    public static WithdrawalAmountMode effective(WithdrawalAmountMode value) {
        return value == null ? FIXED : value;
    }
}
