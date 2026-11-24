package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;

@Service
@Slf4j
@RequiredArgsConstructor
public class BybitOrderThanksWorker {

    private static final Duration THANKS_WINDOW = Duration.ofMinutes(3);

    private final BybitOrderBindingRepository bindingRepository;
    private final BybitGateway bybitGateway;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final WorkspaceSecretService workspaceSecretService;
    private final BybitChatService chatService;

    @Scheduled(fixedDelay = 5_000)
    public void sendPendingThanks() {
        for (BybitOrderBindingEntity binding : bindingRepository
                .findTop20ByStatusAndCompletionThanksRequestedTrueAndCompletionThanksProcessedFalseOrderById(
                        OrderBindingStatus.RELEASED
                )) {
            try {
                sendThanks(binding);
            } catch (RuntimeException exception) {
                log.warn("Could not process Bybit order thanks: orderId={}, message={}",
                        binding.getBybitOrderId(), exception.getMessage());
            }
        }
    }

    private void sendThanks(BybitOrderBindingEntity binding) {
        if (bindingRepository.claimCompletionThanks(binding.getId(), OrderBindingStatus.RELEASED) == 0) {
            return;
        }

        WithdrawalRequestEntity withdrawal = binding.getWithdrawalRequest();
        String orderId = binding.getBybitOrderId();
        Instant completedAt = withdrawal.getCompletedAt();
        if (withdrawal.getStatus() != WithdrawalStatus.COMPLETED
                || !orderId.equals(withdrawal.getBybitOrderId())
                || completedAt == null) {
            return;
        }

        WorkspaceEntity workspace = binding.getWorkspace() == null ? withdrawal.getWorkspace() : binding.getWorkspace();
        Optional<BybitP2pOrder> order = workspace == null
                ? bybitGateway.fetchOrder(orderId)
                : bybitCredentialsContext.callWith(
                        workspaceSecretService.bybitCredentials(workspace),
                        () -> bybitGateway.fetchOrder(orderId)
                );
        Instant paidAt = order.filter(details -> orderId.equals(details.bybitOrderId()))
                .map(BybitP2pOrder::paymentConfirmedAt)
                .orElse(null);
        if (paidAt != null && !completedAt.isBefore(paidAt) && !completedAt.isAfter(paidAt.plus(THANKS_WINDOW))) {
            chatService.sendOrderThanks(workspace, withdrawal);
        }
    }
}
