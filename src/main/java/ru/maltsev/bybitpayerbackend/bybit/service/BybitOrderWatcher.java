package ru.maltsev.bybitpayerbackend.bybit.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.ai.service.AiChatAgentService;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitOrderBindingEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitOrderBindingRepository;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalAmountMode;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalPaymentRules;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

@Service
@Slf4j
public class BybitOrderWatcher {

    private final BybitGateway bybitGateway;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final WorkspaceSecretService workspaceSecretService;
    private final WorkspaceRepository workspaceRepository;
    private final WithdrawalRequestRepository withdrawalRepository;
    private final BybitOrderBindingRepository bindingRepository;
    private final ForeignBybitOrderService foreignOrderService;
    private final AdvertisementManager advertisementManager;
    private final BybitChatService chatService;
    private final AiChatAgentService aiChatAgentService;
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
        this(
                bybitGateway,
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
                clock
        );
    }

    public BybitOrderWatcher(
            BybitGateway bybitGateway,
            BybitCredentialsContext bybitCredentialsContext,
            WorkspaceSecretService workspaceSecretService,
            WorkspaceRepository workspaceRepository,
            WithdrawalRequestRepository withdrawalRepository,
            BybitOrderBindingRepository bindingRepository,
            ForeignBybitOrderService foreignOrderService,
            AdvertisementManager advertisementManager,
            BybitChatService chatService,
            WithdrawalEventService eventService,
            Clock clock
    ) {
        this(
                bybitGateway,
                bybitCredentialsContext,
                workspaceSecretService,
                workspaceRepository,
                withdrawalRepository,
                bindingRepository,
                foreignOrderService,
                advertisementManager,
                chatService,
                null,
                eventService,
                clock
        );
    }

    @Autowired
    public BybitOrderWatcher(
            BybitGateway bybitGateway,
            BybitCredentialsContext bybitCredentialsContext,
            WorkspaceSecretService workspaceSecretService,
            WorkspaceRepository workspaceRepository,
            WithdrawalRequestRepository withdrawalRepository,
            BybitOrderBindingRepository bindingRepository,
            ForeignBybitOrderService foreignOrderService,
            AdvertisementManager advertisementManager,
            BybitChatService chatService,
            AiChatAgentService aiChatAgentService,
            WithdrawalEventService eventService,
            Clock clock
    ) {
        this.bybitGateway = bybitGateway;
        this.bybitCredentialsContext = bybitCredentialsContext;
        this.workspaceSecretService = workspaceSecretService;
        this.workspaceRepository = workspaceRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.bindingRepository = bindingRepository;
        this.foreignOrderService = foreignOrderService;
        this.advertisementManager = advertisementManager;
        this.chatService = chatService;
        this.aiChatAgentService = aiChatAgentService;
        this.eventService = eventService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bybit.p2p-poll-interval:5s}")
    @Transactional
    public void pollActiveOrders() {
        if (workspaceRepository == null) {
            pollActiveOrdersLegacy();
            return;
        }
        for (WorkspaceEntity workspace : workspaceRepository.findByEnabledTrueAndDeletedAtIsNullOrderByCreatedAtAscIdAsc()) {
            pollActiveOrders(workspace);
        }
    }

    private void pollActiveOrdersLegacy() {
        List<BybitP2pOrder> orders = bybitGateway.fetchActiveOrders();
        Set<String> activeOrderIds = new HashSet<>();
        boolean publicationChanged = false;
        for (BybitP2pOrder order : orders) {
            activeOrderIds.add(order.bybitOrderId());
            publicationChanged = processOrderLegacy(order) || publicationChanged;
        }
        publicationChanged = syncMissingBoundOrdersLegacy(activeOrderIds) || publicationChanged;
        foreignOrderService.removeMissingOrders(activeOrderIds);
        if (publicationChanged) {
            advertisementManager.rebuildPublication();
        }
    }

    private void pollActiveOrders(WorkspaceEntity workspace) {
        bybitCredentialsContext.runWith(
                workspaceSecretService.bybitCredentials(workspace),
                () -> {
                    List<BybitP2pOrder> orders = bybitGateway.fetchActiveOrders();
                    Set<String> activeOrderIds = new HashSet<>();
                    boolean publicationChanged = false;
                    if (!orders.isEmpty()) {
                        log.debug("Active Bybit orders fetched: workspace={}, count={}", workspace.getPublicId(), orders.size());
                    }
                    for (BybitP2pOrder order : orders) {
                        activeOrderIds.add(order.bybitOrderId());
                        publicationChanged = processOrder(workspace, order) || publicationChanged;
                    }
                    publicationChanged = syncMissingBoundOrders(workspace, activeOrderIds) || publicationChanged;
                    foreignOrderService.removeMissingOrders(workspace, activeOrderIds);
                    if (publicationChanged) {
                        advertisementManager.rebuildPublication(workspace);
                    }
                }
        );
    }

    private boolean processOrder(WorkspaceEntity workspace, BybitP2pOrder order) {
        return bindingRepository.findByWorkspaceAndBybitOrderId(workspace, order.bybitOrderId())
                .map(binding -> binding.getStatus() == OrderBindingStatus.ACTIVE
                        && syncBoundOrder(binding, order))
                .orElseGet(() -> bindOrMarkForeign(workspace, order));
    }

    private boolean processOrderLegacy(BybitP2pOrder order) {
        return bindingRepository.findByBybitOrderId(order.bybitOrderId())
                .map(binding -> binding.getStatus() == OrderBindingStatus.ACTIVE
                        && syncBoundOrder(binding, order))
                .orElseGet(() -> bindOrMarkForeignLegacy(order));
    }

    private boolean syncBoundOrder(BybitOrderBindingEntity binding, BybitP2pOrder order) {
        WithdrawalRequestEntity withdrawal = binding.getWithdrawalRequest();
        updateOrderAmounts(withdrawal, order);
        if (order.cancelled()) {
            return returnWithdrawalToWork(binding, withdrawal, order);
        }
        if (order.finished()) {
            completeWithdrawal(binding, withdrawal, order);
            return false;
        }
        if (order.paid() && withdrawal.getStatus() == WithdrawalStatus.PAYMENT_IN_PROGRESS) {
            boolean autoReleaseEnabled = aiChatAgentService == null
                    ? WithdrawalPaymentRules.isAutoReleaseEnabled(withdrawal.getPayerBankType(), withdrawal.getWithdrawalMethod())
                    : aiChatAgentService.isAutoReceiptEnabled(withdrawal);
            Instant now = Instant.now(clock);
            withdrawal.setStatus(WithdrawalStatus.PAYMENT_VERIFICATION);
            withdrawal.setPaidAt(now);
            if (autoReleaseEnabled) {
                withdrawal.setVerificationStartedAt(now);
                withdrawal.setAttentionRequired(false);
                withdrawal.setLastWarning(null);
            } else {
                withdrawal.setVerificationStartedAt(null);
                withdrawal.setAttentionRequired(true);
                withdrawal.setLastWarning("Платеж ожидает ручной проверки оператором");
            }
            withdrawalRepository.save(withdrawal);
            eventService.add(withdrawal, WithdrawalEventType.ORDER_PAID, "Bybit order marked as paid");
            if (autoReleaseEnabled) {
                eventService.add(withdrawal, WithdrawalEventType.MAIL_CHECK_STARTED, "Mail verification started");
            } else {
                eventService.add(
                        withdrawal,
                        WithdrawalEventType.ATTENTION_REQUIRED,
                        "Payment requires manual operator verification"
                );
            }
            log.info(
                    "Bybit order marked as paid: orderId={}, withdrawalId={}",
                    order.bybitOrderId(),
                    withdrawal.getId()
            );
        }
        return false;
    }

    private boolean bindOrMarkForeign(WorkspaceEntity workspace, BybitP2pOrder order) {
        if (foreignOrderService.refreshIfTracked(workspace, order)) {
            return false;
        }

        List<WithdrawalRequestEntity> matchingWithdrawals = withdrawalRepository
                .findByWorkspaceAndStatusOrderByCreatedAtAscIdAsc(workspace, WithdrawalStatus.IN_WORK)
                .stream()
                .filter(withdrawal -> orderAmountMatches(withdrawal, order.amountRub()))
                .toList();
        if (matchingWithdrawals.isEmpty()) {
            String reason = "No IN_WORK withdrawal matching this amount";
            foreignOrderService.upsert(workspace, order, reason);
            return false;
        }

        WithdrawalRequestEntity withdrawal = matchingWithdrawals.getFirst();
        Instant now = Instant.now(clock);
        withdrawal.setStatus(WithdrawalStatus.PAYMENT_IN_PROGRESS);
        withdrawal.setBybitOrderId(order.bybitOrderId());
        withdrawal.setBybitOrderAmountRub(order.amountRub());
        withdrawal.setBybitOrderQuantityUsdt(order.quantityUsdt());
        withdrawal.setBybitOrderFeeUsdt(order.feeUsdt());
        withdrawal.setOrderFoundAt(now);
        withdrawalRepository.save(withdrawal);

        BybitOrderBindingEntity binding = new BybitOrderBindingEntity();
        binding.setWorkspace(workspace);
        binding.setBybitOrderId(order.bybitOrderId());
        binding.setWithdrawalRequest(withdrawal);
        binding.setAmountRub(order.amountRub());
        binding.setStatus(OrderBindingStatus.ACTIVE);
        binding.setCreatedAt(now);
        bindingRepository.save(binding);

        eventService.add(withdrawal, WithdrawalEventType.ORDER_FOUND, "Bybit order matched withdrawal");
        if (aiChatAgentService == null) {
            chatService.sendRequisites(withdrawal);
        } else {
            aiChatAgentService.startForOrder(workspace, withdrawal);
        }
        log.info(
                "Bybit order bound to withdrawal: orderId={}, withdrawalId={}, amountRub={}",
                order.bybitOrderId(),
                withdrawal.getId(),
                order.amountRub()
        );
        return true;
    }

    private boolean bindOrMarkForeignLegacy(BybitP2pOrder order) {
        if (foreignOrderService.refreshIfTracked(order)) {
            return false;
        }

        List<WithdrawalRequestEntity> matchingWithdrawals = withdrawalRepository
                .findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus.IN_WORK)
                .stream()
                .filter(withdrawal -> orderAmountMatches(withdrawal, order.amountRub()))
                .toList();
        if (matchingWithdrawals.isEmpty()) {
            String reason = "No IN_WORK withdrawal matching this amount";
            foreignOrderService.upsert(order, reason);
            return false;
        }

        WithdrawalRequestEntity withdrawal = matchingWithdrawals.getFirst();
        Instant now = Instant.now(clock);
        withdrawal.setStatus(WithdrawalStatus.PAYMENT_IN_PROGRESS);
        withdrawal.setBybitOrderId(order.bybitOrderId());
        withdrawal.setBybitOrderAmountRub(order.amountRub());
        withdrawal.setBybitOrderQuantityUsdt(order.quantityUsdt());
        withdrawal.setBybitOrderFeeUsdt(order.feeUsdt());
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
        if (aiChatAgentService == null) {
            chatService.sendRequisites(withdrawal);
        } else {
            aiChatAgentService.startForOrder(withdrawal.getWorkspace(), withdrawal);
        }
        return true;
    }

    private boolean syncMissingBoundOrders(WorkspaceEntity workspace, Set<String> activeOrderIds) {
        boolean publicationChanged = false;
        for (BybitOrderBindingEntity binding : bindingRepository.findAllByWorkspaceAndStatus(workspace, OrderBindingStatus.ACTIVE)) {
            if (activeOrderIds.contains(binding.getBybitOrderId())) {
                continue;
            }
            try {
                publicationChanged = bybitCredentialsContext.callWith(
                        workspaceSecretService.bybitCredentials(workspace),
                        () -> bybitGateway.fetchOrder(binding.getBybitOrderId())
                )
                        .map(order -> syncBoundOrder(binding, order))
                        .orElse(false) || publicationChanged;
            } catch (Exception exception) {
                WithdrawalRequestEntity withdrawal = binding.getWithdrawalRequest();
                withdrawal.setAttentionRequired(true);
                withdrawal.setLastError(exception.getMessage());
                withdrawalRepository.save(withdrawal);
                log.warn(
                        "Bound Bybit order status refresh failed: orderId={}, withdrawalId={}, message={}",
                        binding.getBybitOrderId(),
                        withdrawal.getId(),
                        exception.getMessage()
                );
            }
        }
        return publicationChanged;
    }

    private boolean syncMissingBoundOrdersLegacy(Set<String> activeOrderIds) {
        boolean publicationChanged = false;
        for (BybitOrderBindingEntity binding : bindingRepository.findAllByStatus(OrderBindingStatus.ACTIVE)) {
            if (activeOrderIds.contains(binding.getBybitOrderId())) {
                continue;
            }
            try {
                publicationChanged = bybitGateway.fetchOrder(binding.getBybitOrderId())
                        .map(order -> syncBoundOrder(binding, order))
                        .orElse(false) || publicationChanged;
            } catch (Exception exception) {
                WithdrawalRequestEntity withdrawal = binding.getWithdrawalRequest();
                withdrawal.setAttentionRequired(true);
                withdrawal.setLastError(exception.getMessage());
                withdrawalRepository.save(withdrawal);
            }
        }
        return publicationChanged;
    }

    private boolean returnWithdrawalToWork(
            BybitOrderBindingEntity binding,
            WithdrawalRequestEntity withdrawal,
            BybitP2pOrder order
    ) {
        String cancelledOrderId = binding.getBybitOrderId();
        binding.setStatus(OrderBindingStatus.CANCELLED);
        bindingRepository.save(binding);

        withdrawal.setStatus(WithdrawalStatus.NEW);
        withdrawal.setBybitOrderId(null);
        withdrawal.setBybitOrderAmountRub(null);
        withdrawal.setBybitOrderQuantityUsdt(null);
        withdrawal.setBybitOrderFeeUsdt(null);
        withdrawal.setOrderFoundAt(null);
        withdrawal.setRequisitesSentAt(null);
        withdrawal.setPaidAt(null);
        withdrawal.setVerificationStartedAt(null);
        withdrawal.setAttentionRequired(false);
        withdrawal.setLastError(null);
        withdrawal.setLastWarning(null);
        withdrawalRepository.save(withdrawal);

        eventService.add(
                withdrawal,
                WithdrawalEventType.ORDER_CANCELLED,
                "Bybit order cancelled: " + cancelledOrderId,
                "{\"status\":\"" + order.status() + "\"}"
        );
        eventService.add(
                withdrawal,
                WithdrawalEventType.WITHDRAWAL_RETURNED_TO_WORK,
                "Withdrawal returned to publication after order cancellation"
        );
        log.info(
                "Cancelled Bybit order detached: orderId={}, withdrawalId={}, status={}",
                cancelledOrderId,
                withdrawal.getId(),
                order.status()
        );
        return true;
    }

    private void completeWithdrawal(
            BybitOrderBindingEntity binding,
            WithdrawalRequestEntity withdrawal,
            BybitP2pOrder order
    ) {
        binding.setStatus(OrderBindingStatus.RELEASED);
        bindingRepository.save(binding);

        withdrawal.setStatus(WithdrawalStatus.COMPLETED);
        withdrawal.setCompletedAt(Instant.now(clock));
        withdrawal.setCompletionSeen(false);
        withdrawal.setAttentionRequired(false);
        withdrawal.setLastError(null);
        withdrawal.setLastWarning(null);
        withdrawalRepository.save(withdrawal);
        eventService.add(
                withdrawal,
                WithdrawalEventType.ORDER_COMPLETED_EXTERNALLY,
                "Bybit order was completed manually",
                "{\"status\":\"" + order.status() + "\"}"
        );
        log.info(
                "Withdrawal completed from external Bybit status: orderId={}, withdrawalId={}",
                binding.getBybitOrderId(),
                withdrawal.getId()
        );
    }

    private void updateOrderAmounts(WithdrawalRequestEntity withdrawal, BybitP2pOrder order) {
        withdrawal.setBybitOrderAmountRub(order.amountRub());
        withdrawal.setBybitOrderQuantityUsdt(order.quantityUsdt());
        withdrawal.setBybitOrderFeeUsdt(order.feeUsdt());
    }

    private boolean orderAmountMatches(WithdrawalRequestEntity withdrawal, BigDecimal orderAmountRub) {
        if (orderAmountRub == null) {
            return false;
        }
        if (WithdrawalAmountMode.effective(withdrawal.getAmountMode()) == WithdrawalAmountMode.FIXED) {
            return withdrawal.getAmountRub() != null
                    && orderAmountRub.compareTo(withdrawal.getAmountRub()) == 0;
        }
        BigDecimal amountMinRub = withdrawal.getAmountMinRub();
        BigDecimal amountMaxRub = withdrawal.getAmountMaxRub();
        return amountMinRub != null
                && amountMaxRub != null
                && orderAmountRub.compareTo(amountMinRub) >= 0
                && orderAmountRub.compareTo(amountMaxRub) <= 0;
    }
}
