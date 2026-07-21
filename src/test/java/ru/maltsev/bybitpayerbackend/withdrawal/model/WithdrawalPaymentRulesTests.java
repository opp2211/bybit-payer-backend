package ru.maltsev.bybitpayerbackend.withdrawal.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WithdrawalPaymentRulesTests {

    @Test
    void buildsAdvertisementTailForConfiguredPaymentCombinations() {
        assertThat(WithdrawalPaymentRules.advertisementTail(
                PayerBankType.TBANK_AUTO,
                WithdrawalMethod.SBP,
                true,
                false
        )).isEqualTo("Принимаю на карту 3 лица по СБП");
        assertThat(WithdrawalPaymentRules.advertisementTail(
                PayerBankType.TBANK_AUTO,
                WithdrawalMethod.SBP,
                false,
                false
        )).isEqualTo("Принимаю на личную карту / карту жены по СБП");
        assertThat(WithdrawalPaymentRules.advertisementTail(
                PayerBankType.TBANK_AUTO,
                WithdrawalMethod.CARD_NUMBER,
                true,
                true
        )).isEqualTo("Принимаю на Т-банк 3 лица по номеру карты (СБП нет!)");
        assertThat(WithdrawalPaymentRules.advertisementTail(
                PayerBankType.TBANK_AUTO,
                WithdrawalMethod.CARD_NUMBER,
                false,
                false
        )).isEqualTo("Принимаю на личную карту / карту жены по номеру карты (СБП нет!) "
                + "(Возможна комиссия, если у вас закончился лимит бесплатных переводов)");
        assertThat(WithdrawalPaymentRules.advertisementTail(
                PayerBankType.SBERBANK,
                WithdrawalMethod.ACCOUNT_NUMBER,
                true,
                false
        )).isEqualTo("Принимаю СБЕР-СБЕР по номеру счета на 3 лицо");
        assertThat(WithdrawalPaymentRules.advertisementTail(
                PayerBankType.ANY_BANK,
                WithdrawalMethod.SBP,
                false,
                false
        )).isEqualTo("Принимаю на личную карту / карту жены по СБП");
    }

    @Test
    void queueGroupIncludesMethodAndAdvertisementFlags() {
        assertThat(WithdrawalPaymentRules.queueGroupKey(
                PayerBankType.TBANK_AUTO,
                WithdrawalMethod.CARD_NUMBER,
                true,
                false
        )).isEqualTo("TBANK_AUTO:CARD_NUMBER:THIRD_PARTY:OTHER_CARD");
    }
}
