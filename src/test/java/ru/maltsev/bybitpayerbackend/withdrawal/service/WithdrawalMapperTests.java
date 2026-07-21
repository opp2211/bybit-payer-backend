package ru.maltsev.bybitpayerbackend.withdrawal.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;

class WithdrawalMapperTests {

    private final WithdrawalMapper mapper = new WithdrawalMapper();

    @Test
    void mapsLegacyWithdrawalWithoutMethodAsSbpThirdPartyTransfer() {
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(1L);
        withdrawal.setPublicId("ABC1234");
        withdrawal.setAmountRub(BigDecimal.valueOf(2420));
        withdrawal.setPayerBankType(PayerBankType.TBANK_AUTO);
        withdrawal.setStatus(WithdrawalStatus.NEW);
        withdrawal.setCompletionSeen(true);
        withdrawal.setCreatedAt(Instant.parse("2026-07-20T09:51:42Z"));

        WithdrawalResponse response = mapper.toResponse(withdrawal);

        assertThat(response.withdrawalMethod()).isEqualTo("SBP");
        assertThat(response.withdrawalMethodTitle()).isEqualTo("СБП");
        assertThat(response.thirdPartyTransfer()).isTrue();
        assertThat(response.requireSenderFirstParty()).isFalse();
    }
}
