package ru.maltsev.bybitpayerbackend.withdrawal.model;

public enum PayerBankType {
    TBANK_AUTO(
            "Т-банк (автоподтверждение)",
            "Принимаю платеж только с Т-банка, понадобится чек с офф. почты банка мне на почту",
            true
    ),
    SBERBANK(
            "Сбербанк",
            "Принимаю платеж только со Сбербанка",
            false
    ),
    ANY_BANK(
            "Любой банк",
            "Принимаю платеж с любого банка!",
            false
    );

    private final String title;
    private final String advertisementIntro;
    private final boolean autoReleaseEnabled;

    PayerBankType(String title, String advertisementIntro, boolean autoReleaseEnabled) {
        this.title = title;
        this.advertisementIntro = advertisementIntro;
        this.autoReleaseEnabled = autoReleaseEnabled;
    }

    public String getTitle() {
        return title;
    }

    public String getAdvertisementIntro() {
        return advertisementIntro;
    }

    public boolean isAutoReleaseEnabled() {
        return autoReleaseEnabled;
    }

    public static PayerBankType effective(PayerBankType payerBankType) {
        return payerBankType == null ? TBANK_AUTO : payerBankType;
    }
}
