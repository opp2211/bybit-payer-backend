package ru.maltsev.bybitpayerbackend.bybit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;

class AdvertisementDescriptionBuilderTests {

    private final AdvertisementDescriptionBuilder builder = new AdvertisementDescriptionBuilder();

    @Test
    void usesLatinInitialForTBankInAdDescriptionOnly() {
        String description = builder.build(
                PayerBankType.TBANK_AUTO,
                WithdrawalMethod.CARD_NUMBER,
                false,
                true,
                false,
                List.of(new BigDecimal("2420"))
        );

        assertThat(description)
                .contains("Принимаю платеж только с T-банка")
                .contains("Принимаю на личную карту T-банка")
                .doesNotContain("Т-банк", "Т-банка");
        assertThat(PayerBankType.TBANK_AUTO.getTitle()).isEqualTo("Т-банк (авто)");
    }

    @Test
    void usesLatinInitialForSberbankInAdDescriptionOnly() {
        String description = builder.build(
                PayerBankType.SBERBANK,
                WithdrawalMethod.ACCOUNT_NUMBER,
                true,
                false,
                false,
                List.of(new BigDecimal("2420"))
        );

        assertThat(description)
                .contains("Принимаю платеж только со Sбербанка")
                .contains("Принимаю SБЕР-SБЕР")
                .doesNotContain("Сбербанка", "СБЕР-СБЕР");
        assertThat(PayerBankType.SBERBANK.getTitle()).isEqualTo("Сбербанк");
    }
}
