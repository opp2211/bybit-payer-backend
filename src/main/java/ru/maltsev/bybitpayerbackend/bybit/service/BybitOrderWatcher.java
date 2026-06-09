package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

@Service
@Slf4j
public class BybitOrderWatcher {

    private final BybitGateway bybitGateway;
    private final WithdrawalRequestRepository withdrawalRepository;
    private final BybitOrderBindingRepository bindingRepository;
    private final ForeignBybitOrderService foreignOrderService;
    private final AdvertisementManager advertisementManager;
    private final BybitChatService chatService;
    private final WithdrawalEventService eventService;
    private final Clock clock;

    public BybitOrderWatcher(
            BybitGateway bybitGateway,
            WithdrawalRequestRepository withdrawalRepository,
            BybitOrderBindingRepository bindingRepository,
            ForeignBybitOrderService foreignOrderService,
            AdvertisementManager advertisementManager,
            BybitChatService chatService,
            WithdrawalEventService eventService,
            Clock clock
    ) {
        this.bybitGateway = bybitGateway;
        this.withdrawalRepository = withdrawalRepository;
        this.bindingRepository = bindingRepository;
        this.foreignOrderService = foreignOrderService;
        this.advertisementManager = advertisementManager;
        this.chatService = chatService;
        this.eventService = eventService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bybit.p2p-poll-interval:5s}")
    @Transactional
    public void pollActiveOrders() {
        List<BybitP2pOrder> orders = bybitGateway.fetchActiveOrders();
        if (!orders.isEmpty()) {
            log.debug("Active Bybit orders fetched: count={}", orders.size());
        }
        for (BybitP2pOrder order : orders) {
            processOrder(order);
        }
    }

    private void processOrder(BybitP2pOrder order) {
        bindingRepository.findByBybitOrderId(order.bybitOrderId())
                .ifPresentOrElse(
                        binding -> syncBoundOrder(binding, order),
                        () -> bindOrMarkForeign(order)
                );
    }

    private void syncBoundOrder(BybitOrderBindingEntity binding, BybitP2pOrder order) {
        WithdrawalRequestEntity withdrawal = binding.getWithdrawalRequest();
        if (order.paid() && withdrawal.getStatus() == WithdrawalStatus.PAYMENT_IN_PROGRESS) {
            Instant now = Instant.now(clock);
            withdrawal.setStatus(WithdrawalStatus.PAYMENT_VERIFICATION);
            withdrawal.setPaidAt(now);
            withdrawal.setVerificationStartedAt(now);
            withdrawalRepository.save(withdrawal);
            eventService.add(withdrawal, WithdrawalEventType.ORDER_PAID, "Bybit order marked as paid");
            eventService.add(withdrawal, WithdrawalEventType.MAIL_CHECK_STARTED, "Mail verification started");
            log.info(
                    "Bybit order marked as paid: orderId={}, withdrawalId={}",
                    order.bybitOrderId(),
                    withdrawal.getId()
            );
        }
    }

    private void bindOrMarkForeign(BybitP2pOrder order) {
        List<WithdrawalRequestEntity> matchingWithdrawals = withdrawalRepository
                .findByStatusAndAmountRubOrderByCreatedAtAscIdAsc(WithdrawalStatus.IN_WORK, order.amountRub());
        if (matchingWithdrawals.size() != 1) {
            String reason = matchingWithdrawals.isEmpty()
                    ? "No IN_WORK withdrawal with this amount"
                    : "More than one IN_WORK withdrawal matched this amount";
            foreignOrderService.upsert(order, reason);
            return;
        }

        WithdrawalRequestEntity withdrawal = matchingWithdrawals.getFirst();
        Instant now = Instant.now(clock);
        withdrawal.setStatus(WithdrawalStatus.PAYMENT_IN_PROGRESS);
        withdrawal.setBybitOrderId(order.bybitOrderId());
        withdrawal.setBybitOrderAmountRub(order.amountRub());
        withdrawal.setOrderFoundAt(now);
        withdrawalRepository.save(withdrawal);

        BybitOrderBindingEntity binding = new BybitOrderBindingEntity();
        binding.setBybitOrderId(order.bybitOrderId());
        binding.setWithdrawalRequest(withdrawal);
        binding.setAmountRub(order.amountRub());
        binding.setStatus(OrderBindingStatus.ACTIVE);
        binding.setCreatedAt(now);
        bindingRepository.save(binding);

        eventService.add(withdrawal, WithdrawalEventType.ORDER_FOUND, "Bybit order matched withdrawal");
        advertisementManager.rebuildPublication();
        chatService.sendRequisites(withdrawal);
        log.info(
                "Bybit order bound to withdrawal: orderId={}, withdrawalId={}, amountRub={}",
                order.bybitOrderId(),
                withdrawal.getId(),
                order.amountRub()
        );
    }
}
