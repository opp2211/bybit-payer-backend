package ru.maltsev.bybitpayerbackend.bybit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalAmountMode;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

class BybitOrderWatcherTests {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    @Test
    void resetsPublicationRateWhenNewOrderIsBound() {
        BybitGateway gateway = mock(BybitGateway.class);
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        BybitOrderBindingRepository bindingRepository = mock(BybitOrderBindingRepository.class);
        ForeignBybitOrderService foreignOrderService = mock(ForeignBybitOrderService.class);
        AdvertisementManager advertisementManager = mock(AdvertisementManager.class);
        BybitChatService chatService = mock(BybitChatService.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(1L);
        withdrawal.setStatus(WithdrawalStatus.IN_WORK);
        withdrawal.setAmountRub(new BigDecimal("10000"));

        when(gateway.fetchActiveOrders()).thenReturn(List.of(order("10")));
        when(bindingRepository.findByBybitOrderId("order-1")).thenReturn(Optional.empty());
        when(bindingRepository.findAllByStatus(OrderBindingStatus.ACTIVE)).thenReturn(List.of());
        when(withdrawalRepository.findForBindingByStatusInOrderByCreatedAtAscIdAsc(
                WithdrawalStatus.ORDER_BINDABLE_STATUSES
        ))
                .thenReturn(List.of(withdrawal));
        when(withdrawalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bindingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BybitOrderWatcher watcher = new BybitOrderWatcher(
                gateway,
                withdrawalRepository,
                bindingRepository,
                foreignOrderService,
                advertisementManager,
                chatService,
                eventService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        watcher.pollActiveOrders();

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PAYMENT_IN_PROGRESS);
        assertThat(withdrawal.getBybitOrderId()).isEqualTo("order-1");
        verify(advertisementManager).rebuildPublication();
    }

    @Test
    void keepsTrackedForeignOrderOutOfWithdrawalBinding() {
        BybitGateway gateway = mock(BybitGateway.class);
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        BybitOrderBindingRepository bindingRepository = mock(BybitOrderBindingRepository.class);
        ForeignBybitOrderService foreignOrderService = mock(ForeignBybitOrderService.class);
        AdvertisementManager advertisementManager = mock(AdvertisementManager.class);
        BybitChatService chatService = mock(BybitChatService.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        BybitP2pOrder activeOrder = order("10");

        when(gateway.fetchActiveOrders()).thenReturn(List.of(activeOrder));
        when(bindingRepository.findByBybitOrderId("order-1")).thenReturn(Optional.empty());
        when(bindingRepository.findAllByStatus(OrderBindingStatus.ACTIVE)).thenReturn(List.of());
        when(foreignOrderService.refreshIfTracked(activeOrder)).thenReturn(true);

        BybitOrderWatcher watcher = new BybitOrderWatcher(
                gateway,
                withdrawalRepository,
                bindingRepository,
                foreignOrderService,
                advertisementManager,
                chatService,
                eventService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        watcher.pollActiveOrders();

        verify(withdrawalRepository, never()).findForBindingByStatusInOrderByCreatedAtAscIdAsc(
                WithdrawalStatus.ORDER_BINDABLE_STATUSES
        );
        verify(foreignOrderService).removeMissingOrders(Set.of("order-1"));
        verify(advertisementManager, never()).rebuildPublication();
    }

    @Test
    void bindsRangeWithdrawalWhenOrderAmountIsInsideInclusiveRange() {
        BybitGateway gateway = mock(BybitGateway.class);
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        BybitOrderBindingRepository bindingRepository = mock(BybitOrderBindingRepository.class);
        ForeignBybitOrderService foreignOrderService = mock(ForeignBybitOrderService.class);
        AdvertisementManager advertisementManager = mock(AdvertisementManager.class);
        BybitChatService chatService = mock(BybitChatService.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(1L);
        withdrawal.setStatus(WithdrawalStatus.IN_WORK);
        withdrawal.setAmountMode(WithdrawalAmountMode.RANGE);
        withdrawal.setAmountMinRub(new BigDecimal("20000"));
        withdrawal.setAmountMaxRub(new BigDecimal("25000"));

        when(gateway.fetchActiveOrders()).thenReturn(List.of(order("10", "20000.50")));
        when(bindingRepository.findByBybitOrderId("order-1")).thenReturn(Optional.empty());
        when(bindingRepository.findAllByStatus(OrderBindingStatus.ACTIVE)).thenReturn(List.of());
        when(withdrawalRepository.findForBindingByStatusInOrderByCreatedAtAscIdAsc(
                WithdrawalStatus.ORDER_BINDABLE_STATUSES
        ))
                .thenReturn(List.of(withdrawal));
        when(withdrawalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bindingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BybitOrderWatcher watcher = new BybitOrderWatcher(
                gateway,
                withdrawalRepository,
                bindingRepository,
                foreignOrderService,
                advertisementManager,
                chatService,
                eventService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        watcher.pollActiveOrders();

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PAYMENT_IN_PROGRESS);
        assertThat(withdrawal.getBybitOrderAmountRub()).isEqualByComparingTo("20000.50");
        verify(advertisementManager).rebuildPublication();
    }

    @Test
    void doesNotResendRequisitesWhenPublicationRebuildFailsAfterBinding() {
        BybitGateway gateway = mock(BybitGateway.class);
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        BybitOrderBindingRepository bindingRepository = mock(BybitOrderBindingRepository.class);
        ForeignBybitOrderService foreignOrderService = mock(ForeignBybitOrderService.class);
        AdvertisementManager advertisementManager = mock(AdvertisementManager.class);
        BybitChatService chatService = mock(BybitChatService.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(188L);
        withdrawal.setStatus(WithdrawalStatus.IN_WORK);
        withdrawal.setAmountRub(new BigDecimal("10000"));
        AtomicReference<BybitOrderBindingEntity> persistedBinding = new AtomicReference<>();

        when(gateway.fetchActiveOrders()).thenReturn(List.of(order("10")));
        when(bindingRepository.findByBybitOrderId("order-1"))
                .thenAnswer(invocation -> Optional.ofNullable(persistedBinding.get()));
        when(bindingRepository.findAllByStatus(OrderBindingStatus.ACTIVE)).thenReturn(List.of());
        when(withdrawalRepository.findForBindingByStatusInOrderByCreatedAtAscIdAsc(
                WithdrawalStatus.ORDER_BINDABLE_STATUSES
        ))
                .thenReturn(List.of(withdrawal));
        when(withdrawalRepository.findById(188L)).thenReturn(Optional.of(withdrawal));
        when(withdrawalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(bindingRepository.save(any())).thenAnswer(invocation -> {
            BybitOrderBindingEntity binding = invocation.getArgument(0);
            persistedBinding.set(binding);
            return binding;
        });
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        doThrow(BusinessException.conflict("Insufficient USDT balance for managed ad"))
                .when(advertisementManager)
                .rebuildPublication();

        BybitOrderWatcher watcher = new BybitOrderWatcher(
                gateway,
                new BybitCredentialsContext(),
                null,
                null,
                withdrawalRepository,
                bindingRepository,
                foreignOrderService,
                advertisementManager,
                chatService,
                null,
                eventService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactionManager
        );

        assertThatCode(watcher::pollActiveOrders).doesNotThrowAnyException();
        assertThatCode(watcher::pollActiveOrders).doesNotThrowAnyException();

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PAYMENT_IN_PROGRESS);
        assertThat(persistedBinding.get()).isNotNull();
        InOrder bindingBeforeChat = inOrder(bindingRepository, transactionManager, chatService);
        bindingBeforeChat.verify(bindingRepository).save(any());
        bindingBeforeChat.verify(transactionManager).commit(transactionStatus);
        bindingBeforeChat.verify(chatService).sendRequisites(any(), any(), any(Boolean.class));
        verify(chatService, times(1)).sendRequisites(any(), any(), any(Boolean.class));
        verify(advertisementManager, times(1)).rebuildPublication();
    }

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

    @Test
    void reactivatesCompletedWithdrawalWhenOrderEntersAppeal() {
        Fixture fixture = new Fixture(WithdrawalStatus.COMPLETED);
        fixture.withdrawal.setCompletedAt(NOW.minusSeconds(120));
        fixture.withdrawal.setCompletionSeen(true);
        fixture.binding.setStatus(OrderBindingStatus.RELEASED);
        when(fixture.gateway.fetchActiveOrders()).thenReturn(List.of(order("30")));
        when(fixture.bindingRepository.findByBybitOrderId("order-1"))
                .thenReturn(Optional.of(fixture.binding));

        fixture.watcher.pollActiveOrders();

        assertThat(fixture.binding.getStatus()).isEqualTo(OrderBindingStatus.ACTIVE);
        assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.APPEAL);
        assertThat(fixture.withdrawal.getCompletedAt()).isNull();
        assertThat(fixture.withdrawal.isCompletionSeen()).isFalse();
        assertThat(fixture.withdrawal.isAttentionRequired()).isTrue();
        assertThat(fixture.withdrawal.getLastWarning()).contains("Bybit");
    }

    @Test
    void mapsBybitObjectioningStatusToAppeal() {
        Fixture fixture = new Fixture(WithdrawalStatus.COMPLETED);
        fixture.binding.setStatus(OrderBindingStatus.RELEASED);
        when(fixture.gateway.fetchActiveOrders()).thenReturn(List.of(order("100")));
        when(fixture.bindingRepository.findByBybitOrderId("order-1"))
                .thenReturn(Optional.of(fixture.binding));

        fixture.watcher.pollActiveOrders();

        assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.APPEAL);
    }

    @Test
    void separatesWaitingForUserObjectionFromAppeal() {
        Fixture fixture = new Fixture(WithdrawalStatus.APPEAL);
        when(fixture.gateway.fetchActiveOrders()).thenReturn(List.of(order("110")));
        when(fixture.bindingRepository.findByBybitOrderId("order-1"))
                .thenReturn(Optional.of(fixture.binding));

        fixture.watcher.pollActiveOrders();

        assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.OBJECTION_REQUIRED);
        assertThat(fixture.withdrawal.getLastWarning()).contains("Bybit");
    }

    @Test
    void cancelsWithdrawalWhenAppealEndsWithCancellation() {
        Fixture fixture = new Fixture(WithdrawalStatus.APPEAL);
        when(fixture.gateway.fetchOrder("order-1")).thenReturn(Optional.of(order("40")));

        fixture.watcher.pollActiveOrders();

        assertThat(fixture.binding.getStatus()).isEqualTo(OrderBindingStatus.CANCELLED);
        assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.CANCELLED);
        assertThat(fixture.withdrawal.getCancelledAt()).isEqualTo(NOW);
        assertThat(fixture.withdrawal.getCompletedAt()).isNull();
        assertThat(fixture.withdrawal.getBybitOrderId()).isEqualTo("order-1");
        verify(fixture.advertisementManager, never()).rebuildPublication();
    }

    @Test
    void completesWithdrawalWhenAppealEndsWithCompletion() {
        Fixture fixture = new Fixture(WithdrawalStatus.OBJECTION_REQUIRED);
        when(fixture.gateway.fetchOrder("order-1")).thenReturn(Optional.of(order("50")));

        fixture.watcher.pollActiveOrders();

        assertThat(fixture.binding.getStatus()).isEqualTo(OrderBindingStatus.RELEASED);
        assertThat(fixture.withdrawal.getStatus()).isEqualTo(WithdrawalStatus.COMPLETED);
        assertThat(fixture.withdrawal.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void paidManualPayerBankTypeRequiresOperatorAttention() {
        BybitGateway gateway = mock(BybitGateway.class);
        WithdrawalRequestRepository withdrawalRepository = mock(WithdrawalRequestRepository.class);
        BybitOrderBindingRepository bindingRepository = mock(BybitOrderBindingRepository.class);
        ForeignBybitOrderService foreignOrderService = mock(ForeignBybitOrderService.class);
        AdvertisementManager advertisementManager = mock(AdvertisementManager.class);
        BybitChatService chatService = mock(BybitChatService.class);
        WithdrawalEventService eventService = mock(WithdrawalEventService.class);
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setId(1L);
        withdrawal.setStatus(WithdrawalStatus.PAYMENT_IN_PROGRESS);
        withdrawal.setPayerBankType(PayerBankType.SBERBANK);
        withdrawal.setBybitOrderId("order-1");
        BybitOrderBindingEntity binding = new BybitOrderBindingEntity();
        binding.setBybitOrderId("order-1");
        binding.setWithdrawalRequest(withdrawal);
        binding.setStatus(OrderBindingStatus.ACTIVE);

        when(gateway.fetchActiveOrders()).thenReturn(List.of(order("20")));
        when(bindingRepository.findByBybitOrderId("order-1")).thenReturn(Optional.of(binding));
        when(bindingRepository.findAllByStatus(OrderBindingStatus.ACTIVE)).thenReturn(List.of());
        when(withdrawalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BybitOrderWatcher watcher = new BybitOrderWatcher(
                gateway,
                withdrawalRepository,
                bindingRepository,
                foreignOrderService,
                advertisementManager,
                chatService,
                eventService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        watcher.pollActiveOrders();

        assertThat(withdrawal.getStatus()).isEqualTo(WithdrawalStatus.PAYMENT_VERIFICATION);
        assertThat(withdrawal.getPaidAt()).isEqualTo(NOW);
        assertThat(withdrawal.getVerificationStartedAt()).isNull();
        assertThat(withdrawal.isAttentionRequired()).isTrue();
        assertThat(withdrawal.getLastWarning()).contains("ручной проверки");
        verify(eventService).add(withdrawal, WithdrawalEventType.ORDER_PAID, "Bybit order marked as paid");
        verify(eventService).add(
                withdrawal,
                WithdrawalEventType.ATTENTION_REQUIRED,
                "Payment requires manual operator verification"
        );
        verify(eventService, never()).add(withdrawal, WithdrawalEventType.MAIL_CHECK_STARTED, "Mail verification started");
    }

    private static BybitP2pOrder order(String status) {
        return order(status, "10000");
    }

    private static BybitP2pOrder order(String status, String amountRub) {
        return new BybitP2pOrder(
                "order-1",
                new BigDecimal(amountRub),
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
