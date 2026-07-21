package ru.maltsev.bybitpayerbackend.withdrawal.model;

public enum WithdrawalMethod {
    SBP("СБП"),
    CARD_NUMBER("По номеру карты"),
    ACCOUNT_NUMBER("По номеру счета");

    private final String title;

    WithdrawalMethod(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public static WithdrawalMethod effective(WithdrawalMethod withdrawalMethod) {
        return withdrawalMethod == null ? SBP : withdrawalMethod;
    }
}
