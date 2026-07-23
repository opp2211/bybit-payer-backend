package ru.maltsev.bybitpayerbackend.bybit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.AdUpdateCommand;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitManagedAdStateRepository;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalAmountMode;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

class AdvertisementManagerTests {

    @Test
    void buildsSingleWithdrawalPreviewFromCurrentRate() {
        AdvertisementManager manager = manager(
                mock(WithdrawalRequestRepository.class),
                mock(BybitManagedAdStateRepository.class),
                mock(BybitGateway.class),
                new BybitProperties(),
                new BusinessProperties()
        );

        AdvertisementPreview preview = manager.buildSingleWithdrawalPreview(
                new BigDecimal("12345"),
                PayerBankType.TBANK_AUTO,
                WithdrawalMethod.CARD_NUMBER,
                false,
                true,
                true,
                new BigDecimal("95.50")
        );

        assertThat(preview.rate()).isEqualByComparingTo("95.50");
        assertThat(preview.minRub()).isEqualByComparingTo("1000");
        assertThat(preview.maxRub()).isEqualByComparingTo("12345");
        assertThat(preview.amountMinRub()).isEqualByComparingTo("12345");
        assertThat(preview.amountMaxRub()).isEqualByComparingTo("12345");
        assertThat(preview.quantityUsdt()).isEqualByComparingTo("129.2671");
        assertThat(preview.description()).contains("12345");
        assertThat(preview.description()).doesNotContain("12345 /");
        assertThat(preview.description()).contains(
                "Работаю только с 1 лицами",
                "Заходите только на сумму 12345 руб. - другие суммы - отмена!"
        );
    }

    @Test
    void buildsRangeWithdrawalPreviewAndAdjustsAdvertisementRange() {
        AdvertisementManager manager = manager(
                mock(WithdrawalRequestRepository.class),
                mock(BybitManagedAdStateRepository.class),
                mock(BybitGateway.class),
                new BybitProperties(),
                new BusinessProperties()
        );

        AdvertisementPreview preview = manager.buildSingleWithdrawalPreview(
                WithdrawalAmountMode.RANGE,
                null,
                new BigDecimal("20000"),
                new BigDecimal("25000"),
                PayerBankType.ANY_BANK,
                WithdrawalMethod.SBP,
                false,
                false,
                false,
                new BigDecimal("100")
        );

        assertThat(preview.minRub()).isEqualByComparingTo("1000");
        assertThat(preview.maxRub()).isEqualByComparingTo("25000");
        assertThat(preview.amountMinRub()).isEqualByComparingTo("20000");
        assertThat(preview.amountMaxRub()).isEqualByComparingTo("25000");
        assertThat(preview.quantityUsdt()).isEqualByComparingTo("250.0000");
        assertThat(preview.description()).contains(
                "Заходите только на сумму в диапазоне 20000 - 25000 руб. - другие суммы - отмена!"
        );
    }

    @Test
    void keepsDescriptionWhenCurrentRateIsMissing() {
        AdvertisementManager manager = manager(
                mock(WithdrawalRequestRepository.class),
                mock(BybitManagedAdStateRepository.class),
                mock(BybitGateway.class),
                new BybitProperties(),
                new BusinessProperties()
        );

        AdvertisementPreview preview = manager.buildSingleWithdrawalPreview(
                new BigDecimal("5000"),
                PayerBankType.ANY_BANK,
                WithdrawalMethod.SBP,
                true,
                false,
                false,
                null
        );

        assertThat(preview.rate()).isNull();
        assertThat(preview.quantityUsdt()).isNull();
        assertThat(preview.minRub()).isEqualByComparingTo("1000");
        assertThat(preview.maxRub()).isEqualByComparingTo("10000");
        assertThat(preview.description()).contains("5000");
    }

    @Test
    void refreshesRateFromFifteenthPositionThenMovesTowardSeventh() {
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        BybitManagedAdStateRepository stateRepository = mock(BybitManagedAdStateRepository.class);
        BybitGateway gateway = mock(BybitGateway.class);
        BybitProperties bybitProperties = new BybitProperties();
        BusinessProperties businessProperties = new BusinessProperties();
        BybitManagedAdStateEntity state = new BybitManagedAdStateEntity();
        List<AdUpdateCommand> commands = new ArrayList<>();

        bybitProperties.setP2pAdId("ad-1");
        bybitProperties.setRateSourceAdIndex(15);
        bybitProperties.setRateSourceMinAdIndex(7);
        state.setBybitAdId("ad-1");
        when(stateRepository.findAll()).thenReturn(List.of(state));
        when(stateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.fetchReferenceRate(any(Integer.class)))
                .thenAnswer(invocation -> {
                    int position = invocation.getArgument(0);
                    return position == 7
                            ? new BigDecimal("75.50")
                            : new BigDecimal("100").subtract(BigDecimal.valueOf(position));
                });
        when(gateway.fetchAvailableUsdtBalance()).thenReturn(new BigDecimal("1000"));
        org.mockito.Mockito.doAnswer(invocation -> {
            commands.add(invocation.getArgument(0));
            return null;
        }).when(gateway).updateManagedAd(any());

        WithdrawalRequestEntity withdrawal = withdrawal(
                1L,
                "10000",
                PayerBankType.TBANK_AUTO,
                "2026-06-09T10:00:00Z"
        );
        when(withdrawalRepository.findByStatusInOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(withdrawal));
        when(withdrawalRepository.findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus.IN_WORK))
                .thenReturn(List.of(withdrawal));

        AdvertisementManager manager = manager(
                withdrawalRepository,
                stateRepository,
                gateway,
                bybitProperties,
                businessProperties
        );

        manager.rebuildPublication();
        manager.refreshPublicationRate();
        manager.refreshPublicationRate();
        manager.refreshPublicationRate();
        manager.refreshPublicationRate();
        manager.rebuildPublication();

        assertThat(commands)
                .extracting(AdUpdateCommand::rate)
                .containsExactly(
                        new BigDecimal("85"),
                        new BigDecimal("85"),
                        new BigDecimal("86"),
                        new BigDecimal("87"),
                        new BigDecimal("88"),
                        new BigDecimal("85")
                );
        assertThat(state.getLastRateSourcePosition()).isEqualTo(15);
        assertThat(state.getNextRateSourcePosition()).isEqualTo(15);
        assertThat(state.getReferenceRate7()).isEqualByComparingTo("75.50");
        assertThat(state.getReferenceRate7WithFee()).isEqualByComparingTo("75.29294440");
    }

    @Test
    void publishesOnlyEarliestPayerBankGroup() {
        Fixture fixture = new Fixture();
        WithdrawalRequestEntity tbankFirst = withdrawal(1L, "2420", PayerBankType.TBANK_AUTO, "2026-06-09T10:00:00Z");
        WithdrawalRequestEntity sberbank = withdrawal(2L, "5000", PayerBankType.SBERBANK, "2026-06-09T10:01:00Z");
        WithdrawalRequestEntity tbankSecond = withdrawal(3L, "7000", PayerBankType.TBANK_AUTO, "2026-06-09T10:02:00Z");
        when(fixture.withdrawalRepository.findByStatusInOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(
                        List.of(tbankFirst, sberbank, tbankSecond),
                        List.of(sberbank, tbankSecond)
                );

        fixture.manager.rebuildPublication();
        fixture.manager.rebuildPublication();

        assertThat(tbankFirst.getStatus()).isEqualTo(WithdrawalStatus.IN_WORK);
        assertThat(sberbank.getStatus()).isEqualTo(WithdrawalStatus.IN_WORK);
        assertThat(tbankSecond.getStatus()).isEqualTo(WithdrawalStatus.QUEUED);
        assertThat(tbankSecond.getQueuePosition()).isEqualTo(1);
        assertThat(fixture.commands)
                .extracting(AdUpdateCommand::description)
                .allSatisfy(description -> assertThat(description)
                        .contains("руб. - другие суммы - отмена!"));
        assertThat(fixture.commands.getFirst().description()).contains("2420 / 7000");
        assertThat(fixture.commands.get(1).description()).contains("5000");
    }

    @Test
    void publishesRangeWithdrawalAlone() {
        Fixture fixture = new Fixture();
        WithdrawalRequestEntity range = rangeWithdrawal(
                1L,
                "RANGE01",
                "20000",
                "25000",
                "2026-06-09T10:00:00Z"
        );
        WithdrawalRequestEntity fixed = withdrawal(2L, "21000", PayerBankType.TBANK_AUTO, "2026-06-09T10:01:00Z");
        when(fixture.withdrawalRepository.findByStatusInOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(range, fixed));

        fixture.manager.rebuildPublication();

        assertThat(range.getStatus()).isEqualTo(WithdrawalStatus.IN_WORK);
        assertThat(fixed.getStatus()).isEqualTo(WithdrawalStatus.QUEUED);
        assertThat(fixed.getQueuePosition()).isEqualTo(1);
        assertThat(fixture.commands).hasSize(1);
        assertThat(fixture.commands.getFirst().minRub()).isEqualByComparingTo("1000");
        assertThat(fixture.commands.getFirst().maxRub()).isEqualByComparingTo("25000");
        assertThat(fixture.commands.getFirst().description()).contains("в диапазоне 20000 - 25000");
    }

    @Test
    void keepsOverflowAmountsQueuedWhenDescriptionLimitIsReached() {
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        BybitManagedAdStateRepository stateRepository = mock(BybitManagedAdStateRepository.class);
        BybitGateway gateway = mock(BybitGateway.class);
        BybitProperties bybitProperties = new BybitProperties();
        BusinessProperties businessProperties = new BusinessProperties();
        BybitManagedAdStateEntity state = new BybitManagedAdStateEntity();
        List<AdUpdateCommand> commands = new ArrayList<>();
        List<WithdrawalRequestEntity> withdrawals = new ArrayList<>();

        businessProperties.setMaxPublishedAmounts(100);
        bybitProperties.setP2pAdId("ad-1");
        bybitProperties.setRateSourceAdIndex(15);
        bybitProperties.setRateSourceMinAdIndex(7);
        for (int index = 0; index < 40; index++) {
            withdrawals.add(withdrawal(
                    (long) index + 1,
                    "900000000000000000000000000000" + index,
                    PayerBankType.TBANK_AUTO,
                    "2026-06-09T10:%02d:00Z".formatted(index)
            ));
        }
        when(stateRepository.findAll()).thenReturn(List.of(state));
        when(stateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(gateway.fetchReferenceRate(any(Integer.class))).thenReturn(new BigDecimal("1"));
        when(gateway.fetchAvailableUsdtBalance()).thenReturn(new BigDecimal("999999999999999999999999999999999999"));
        org.mockito.Mockito.doAnswer(invocation -> {
            commands.add(invocation.getArgument(0));
            return null;
        }).when(gateway).updateManagedAd(any());
        when(withdrawalRepository.findByStatusInOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(withdrawals);

        AdvertisementManager manager = manager(
                withdrawalRepository,
                stateRepository,
                gateway,
                bybitProperties,
                businessProperties
        );

        manager.rebuildPublication();

        long publishedCount = withdrawals.stream()
                .filter(withdrawal -> withdrawal.getStatus() == WithdrawalStatus.IN_WORK)
                .count();
        long queuedCount = withdrawals.stream()
                .filter(withdrawal -> withdrawal.getStatus() == WithdrawalStatus.QUEUED)
                .count();
        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().description()).hasSizeLessThanOrEqualTo(
                AdvertisementDescriptionBuilder.MAX_DESCRIPTION_LENGTH
        );
        assertThat(publishedCount).isGreaterThan(1);
        assertThat(queuedCount).isGreaterThan(0);
        assertThat(withdrawals.getLast().getQueuePosition()).isNotNull();
        assertThat(commands.getFirst().description()).doesNotContain(
                withdrawals.getLast().getAmountRub().toPlainString()
        );
    }

    @Test
    void rejectsPublicationWhenSingleAmountDescriptionExceedsLimit() {
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        BybitManagedAdStateRepository stateRepository = mock(BybitManagedAdStateRepository.class);
        BybitGateway gateway = mock(BybitGateway.class);
        BybitProperties bybitProperties = new BybitProperties();
        BusinessProperties businessProperties = new BusinessProperties();
        WithdrawalRequestEntity withdrawal = withdrawal(
                1L,
                "9".repeat(950),
                PayerBankType.TBANK_AUTO,
                "2026-06-09T10:00:00Z"
        );

        bybitProperties.setP2pAdId("ad-1");
        bybitProperties.setRateSourceAdIndex(15);
        bybitProperties.setRateSourceMinAdIndex(7);
        when(withdrawalRepository.findByStatusInOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(withdrawal));

        AdvertisementManager manager = manager(
                withdrawalRepository,
                stateRepository,
                gateway,
                bybitProperties,
                businessProperties
        );

        assertThatThrownBy(manager::rebuildPublication)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Описание объявления превышает лимит Bybit");
        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.NEW);
        verify(gateway, never()).updateManagedAd(any());
    }

    private AdvertisementManager manager(
            WithdrawalRequestRepository withdrawalRepository,
            BybitManagedAdStateRepository stateRepository,
            BybitGateway gateway,
            BybitProperties bybitProperties,
            BusinessProperties businessProperties
    ) {
        return new AdvertisementManager(
                withdrawalRepository,
                stateRepository,
                mock(WithdrawalEventService.class),
                gateway,
                bybitProperties,
                businessProperties,
                Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    private WithdrawalRequestEntity withdrawal(
            Long id,
            String amountRub,
            PayerBankType payerBankType,
            String createdAt
    ) {
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(id);
        withdrawal.setPublicId("FIXED" + id);
        withdrawal.setAmountMode(WithdrawalAmountMode.FIXED);
        withdrawal.setAmountRub(new BigDecimal(amountRub));
        withdrawal.setAmountMinRub(withdrawal.getAmountRub());
        withdrawal.setAmountMaxRub(withdrawal.getAmountRub());
        withdrawal.setPayerBankType(payerBankType);
        withdrawal.setWithdrawalMethod(
                payerBankType == PayerBankType.SBERBANK
                        ? WithdrawalMethod.ACCOUNT_NUMBER
                        : WithdrawalMethod.SBP
        );
        withdrawal.setThirdPartyTransfer(true);
        withdrawal.setStatus(WithdrawalStatus.NEW);
        withdrawal.setCreatedAt(Instant.parse(createdAt));
        return withdrawal;
    }

    private static WithdrawalRequestEntity rangeWithdrawal(
            Long id,
            String publicId,
            String amountMinRub,
            String amountMaxRub,
            String createdAt
    ) {
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(id);
        withdrawal.setPublicId(publicId);
        withdrawal.setAmountMode(WithdrawalAmountMode.RANGE);
        withdrawal.setAmountMinRub(new BigDecimal(amountMinRub));
        withdrawal.setAmountMaxRub(new BigDecimal(amountMaxRub));
        withdrawal.setPayerBankType(PayerBankType.TBANK_AUTO);
        withdrawal.setWithdrawalMethod(WithdrawalMethod.SBP);
        withdrawal.setThirdPartyTransfer(true);
        withdrawal.setStatus(WithdrawalStatus.NEW);
        withdrawal.setCreatedAt(Instant.parse(createdAt));
        return withdrawal;
    }

    private class Fixture {

        private final WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        private final BybitManagedAdStateRepository stateRepository = mock(BybitManagedAdStateRepository.class);
        private final BybitGateway gateway = mock(BybitGateway.class);
        private final BybitManagedAdStateEntity state = new BybitManagedAdStateEntity();
        private final List<AdUpdateCommand> commands = new ArrayList<>();
        private final AdvertisementManager manager;

        private Fixture() {
            BybitProperties bybitProperties = new BybitProperties();
            bybitProperties.setP2pAdId("ad-1");
            bybitProperties.setRateSourceAdIndex(15);
            bybitProperties.setRateSourceMinAdIndex(7);
            when(stateRepository.findAll()).thenReturn(List.of(state));
            when(stateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(gateway.fetchReferenceRate(any(Integer.class))).thenReturn(new BigDecimal("100"));
            when(gateway.fetchAvailableUsdtBalance()).thenReturn(new BigDecimal("1000"));
            org.mockito.Mockito.doAnswer(invocation -> {
                commands.add(invocation.getArgument(0));
                return null;
            }).when(gateway).updateManagedAd(any());
            manager = manager(
                    withdrawalRepository,
                    stateRepository,
                    gateway,
                    bybitProperties,
                    new BusinessProperties()
            );
        }
    }
}
