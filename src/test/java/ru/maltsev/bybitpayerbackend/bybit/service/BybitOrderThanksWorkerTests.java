package ru.maltsev.bybitpayerbackend.bybit.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;

class BybitOrderThanksWorkerTests {

    private static final Instant PAID_AT = Instant.parse("2026-11-24T10:00:00Z");

    private final BybitOrderBindingRepository bindingRepository = mock(BybitOrderBindingRepository.class);
    private final BybitGateway gateway = mock(BybitGateway.class);
    private final BybitChatService chatService = mock(BybitChatService.class);
    private final BybitOrderThanksWorker worker = new BybitOrderThanksWorker(
            bindingRepository, gateway, new BybitCredentialsContext(), mock(WorkspaceSecretService.class), chatService
    );

    @ParameterizedTest
    @CsvSource({"0,true", "179999,true", "180000,true", "180001,false", "-1,false"})
    void sendsThanksOnlyWithinThreeMinutesOfBybitPaymentTime(long elapsedMillis, boolean sendsThanks) {
        WithdrawalRequestEntity withdrawal = queueCompletion(PAID_AT.plusMillis(elapsedMillis));
        stubOrderPayment(PAID_AT);
        when(bindingRepository.claimCompletionThanks(11L, OrderBindingStatus.RELEASED)).thenReturn(1, 0);

        worker.sendPendingThanks();
        worker.sendPendingThanks();

        verify(chatService, times(sendsThanks ? 1 : 0)).sendOrderThanks(null, withdrawal);
        verify(gateway, times(1)).fetchOrder("order-1");
    }

    @Test
    void skipsThanksWhenBybitDoesNotReturnPaymentTime() {
        queueCompletion(PAID_AT.plusSeconds(30));
        stubOrderPayment(null);
        when(bindingRepository.claimCompletionThanks(11L, OrderBindingStatus.RELEASED)).thenReturn(1);

        worker.sendPendingThanks();

        verifyNoInteractions(chatService);
    }

    private WithdrawalRequestEntity queueCompletion(Instant completedAt) {
        WithdrawalRequestEntity withdrawal = new WithdrawalRequestEntity();
        withdrawal.setBybitOrderId("order-1");
        withdrawal.setStatus(WithdrawalStatus.COMPLETED);
        withdrawal.setCompletedAt(completedAt);
        withdrawal.setPaidAt(completedAt.minusSeconds(30));

        BybitOrderBindingEntity binding = new BybitOrderBindingEntity();
        binding.setId(11L);
        binding.setBybitOrderId("order-1");
        binding.setWithdrawalRequest(withdrawal);
        binding.markReleased();
        when(bindingRepository.findTop20ByStatusAndCompletionThanksRequestedTrueAndCompletionThanksProcessedFalseOrderById(
                OrderBindingStatus.RELEASED
        )).thenReturn(List.of(binding));
        return withdrawal;
    }

    private void stubOrderPayment(Instant paidAt) {
        when(gateway.fetchOrder("order-1")).thenReturn(Optional.of(new BybitP2pOrder(
                "order-1", BigDecimal.TEN, "50", BigDecimal.ONE, BigDecimal.ZERO, null, paidAt
        )));
    }
}
