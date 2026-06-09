package ru.maltsev.bybitpayerbackend.bybit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

class BybitOrderWatcherTests {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    @Test
    void returnsWithdrawalToWorkWhenBoundOrderWasCancelled() {
        Fixture fixture = new Fixture(WithdrawalStatus.PAYMENT_VERIFICATION);
        when(fixture.gateway.fetchOrder("order-1")).thenReturn(Optional.of(order("40")));

        fixture.watcher.pollActiveOrders();

        assertThat(fixture.binding.getStatus()).isEqualTo(OrderBindingStatus.CANCELLED);
        assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.NEW);
        assertThat(fixture.withdrawal.getBybitOrderId()).isNull();
        assertThat(fixture.withdrawal.getVerificationStartedAt()).isNull();
        verify(fixture.advertisementManager).rebuildPublication();
    }

    @Test
    void completesWithdrawalWhenOrderWasReleasedOutsideSystem() {
        Fixture fixture = new Fixture(WithdrawalStatus.PAYMENT_VERIFICATION);
        when(fixture.gateway.fetchOrder("order-1")).thenReturn(Optional.of(order("50")));

        fixture.watcher.pollActiveOrders();

        assertThat(fixture.binding.getStatus()).isEqualTo(OrderBindingStatus.RELEASED);
        assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(fixture.withdrawal.getCompletedAt()).isEqualTo(NOW);
        assertThat(fixture.withdrawal.isCompletionSeen()).isFalse();
        verify(fixture.advertisementManager, never()).rebuildPublication();
    }

    private static BybitP2pOrder order(String status) {
        return new BybitP2pOrder(
                "order-1",
                new BigDecimal("10000"),
                status,
                new BigDecimal("108.25"),
                new BigDecimal("0.30")
        );
    }

    private static class Fixture {

        private final BybitGateway gateway = mock(BybitGateway.class);
        private final WithdrawalRequestRepository withdrawalRepository =
                mock(WithdrawalRequestRepository.class);
        private final BybitOrderBindingRepository bindingRepository =
                mock(BybitOrderBindingRepository.class);
        private final ForeignBybitOrderService foreignOrderService =
                mock(ForeignBybitOrderService.class);
        private final AdvertisementManager advertisementManager =
                mock(AdvertisementManager.class);
        private final BybitChatService chatService = mock(BybitChatService.class);
        private final WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        private final WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        private final BybitOrderBindingEntity binding = new BybitOrderBindingEntity();
        private final BybitOrderWatcher watcher;

        private Fixture(WithdrawalStatus status) {
            withdrawal.setId(1L);
            withdrawal.setStatus(status);
            withdrawal.setBybitOrderId("order-1");
            withdrawal.setBybitOrderAmountRub(new BigDecimal("10000"));
            withdrawal.setVerificationStartedAt(NOW.minusSeconds(60));

            binding.setId(1L);
            binding.setBybitOrderId("order-1");
            binding.setWithdrawalRequest(withdrawal);
            binding.setStatus(OrderBindingStatus.ACTIVE);

            when(gateway.fetchActiveOrders()).thenReturn(List.of());
            when(bindingRepository.findAllByStatus(OrderBindingStatus.ACTIVE))
                    .thenReturn(List.of(binding));
            when(withdrawalRepository.save(org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            watcher = new BybitOrderWatcher(
                    gateway,
                    withdrawalRepository,
                    bindingRepository,
                    foreignOrderService,
                    advertisementManager,
                    chatService,
                    eventService,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );
        }
    }
}
