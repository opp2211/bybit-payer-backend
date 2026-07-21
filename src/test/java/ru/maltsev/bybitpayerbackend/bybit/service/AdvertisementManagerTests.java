package ru.maltsev.bybitpayerbackend.bybit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.AdUpdateCommand;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitManagedAdStateRepository;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

class AdvertisementManagerTests {

    @Test
    void buildsSingleWithdrawalPreviewFromCurrentRate() {
        AdvertisementManager manager = new AdvertisementManager(
                mock(WithdrawalRequestRepository.class),
                mock(BybitManagedAdStateRepository.class),
                mock(WithdrawalEventService.class),
                mock(BybitGateway.class),
                new BybitProperties(),
                new BusinessProperties(),
                Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneOffset.UTC)
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
        assertThat(preview.quantityUsdt()).isEqualByComparingTo("129.2671");
        assertThat(preview.description()).contains("12345");
        assertThat(preview.description()).doesNotContain("12345 /");
        assertThat(preview.description()).startsWith(
                "Работаю только с 1 лицами "
                        + "(Имя Ф. отправителя должны совпадать с верифицированным именем на Bybit) ___ "
                        + "Принимаю платеж только с Т-банка"
        );
    }

    @Test
    void keepsDescriptionWhenCurrentRateIsMissing() {
        AdvertisementManager manager = new AdvertisementManager(
                mock(WithdrawalRequestRepository.class),
                mock(BybitManagedAdStateRepository.class),
                mock(WithdrawalEventService.class),
                mock(BybitGateway.class),
                new BybitProperties(),
                new BusinessProperties(),
                Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneOffset.UTC)
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
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
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

        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(1L);
        withdrawal.setAmountRub(new BigDecimal("10000"));
        withdrawal.setStatus(WithdrawalStatus.NEW);
        withdrawal.setCreatedAt(Instant.parse("2026-06-09T10:00:00Z"));
        when(withdrawalRepository.findByStatusInOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(List.of(withdrawal));
        when(withdrawalRepository.findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus.IN_WORK))
                .thenReturn(List.of(withdrawal));

        AdvertisementManager manager = new AdvertisementManager(
                withdrawalRepository,
                stateRepository,
                eventService,
                gateway,
                bybitProperties,
                businessProperties,
                Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneOffset.UTC)
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
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        BybitManagedAdStateRepository stateRepository = mock(BybitManagedAdStateRepository.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        BybitGateway gateway = mock(BybitGateway.class);
        BybitProperties bybitProperties = new BybitProperties();
        BusinessProperties businessProperties = new BusinessProperties();
        BybitManagedAdStateEntity state = new BybitManagedAdStateEntity();
        List<AdUpdateCommand> commands = new ArrayList<>();

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

        WithdrawalRequestEntity tbankFirst = withdrawal(
                1L,
                "2420",
                PayerBankType.TBANK_AUTO,
                "2026-06-09T10:00:00Z"
        );
        WithdrawalRequestEntity sberbank = withdrawal(
                2L,
                "5000",
                PayerBankType.SBERBANK,
                "2026-06-09T10:01:00Z"
        );
        WithdrawalRequestEntity tbankSecond = withdrawal(
                3L,
                "7000",
                PayerBankType.TBANK_AUTO,
                "2026-06-09T10:02:00Z"
        );
        when(withdrawalRepository.findByStatusInOrderByCreatedAtAscIdAsc(any()))
                .thenReturn(
                        List.of(tbankFirst, sberbank, tbankSecond),
                        List.of(sberbank, tbankSecond)
                );

        AdvertisementManager manager = new AdvertisementManager(
                withdrawalRepository,
                stateRepository,
                eventService,
                gateway,
                bybitProperties,
                businessProperties,
                Clock.fixed(Instant.parse("2026-06-09T12:00:00Z"), ZoneOffset.UTC)
        );

        manager.rebuildPublication();
        manager.rebuildPublication();

        assertThat(tbankFirst.getStatus()).isEqualTo(WithdrawalStatus.IN_WORK);
        assertThat(sberbank.getStatus()).isEqualTo(WithdrawalStatus.IN_WORK);
        assertThat(tbankSecond.getStatus()).isEqualTo(WithdrawalStatus.QUEUED);
        assertThat(tbankSecond.getQueuePosition()).isEqualTo(1);
        assertThat(commands)
                .extracting(AdUpdateCommand::description)
                .containsExactly(
                        "Принимаю платеж только с Т-банка, понадобится чек с офф. почты банка мне на почту ___ Заходите только на сумму 2420 / 7000 руб.  - другие суммы - отмена! ___ Принимаю на карту 3 лица по СБП",
                        "Принимаю платеж только со Сбербанка ___ Заходите только на сумму 5000 руб.  - другие суммы - отмена! ___ Принимаю СБЕР-СБЕР по номеру счета на 3 лицо"
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
        withdrawal.setAmountRub(new BigDecimal(amountRub));
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
}
