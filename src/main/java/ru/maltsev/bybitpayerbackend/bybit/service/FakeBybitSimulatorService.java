package ru.maltsev.bybitpayerbackend.bybit.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.dto.FakeBybitAdResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.FakeBybitChatMessageResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.FakeBybitOrderResponse;
import ru.maltsev.bybitpayerbackend.bybit.gateway.FakeBybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.FakeBybitGateway.SimulatedAd;
import ru.maltsev.bybitpayerbackend.bybit.gateway.FakeBybitGateway.SimulatedChatMessage;
import ru.maltsev.bybitpayerbackend.bybit.gateway.FakeBybitGateway.SimulatedOrder;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;

@Service
@Profile("local")
@RequiredArgsConstructor
public class FakeBybitSimulatorService {

    private final FakeBybitGateway gateway;
    private final WorkspaceRepository workspaceRepository;

    public List<FakeBybitAdResponse> getPublishedAds() {
        Map<String, WorkspaceEntity> workspacesByAdId = workspacesByAdId();
        Map<String, Long> activeOrderCounts = gateway.activeSimulatorOrders().stream()
                .collect(Collectors.groupingBy(SimulatedOrder::bybitAdId, Collectors.counting()));

        return gateway.publishedAds().stream()
                .map(ad -> toAdResponse(
                        ad,
                        workspacesByAdId.get(ad.bybitAdId()),
                        activeOrderCounts.getOrDefault(ad.bybitAdId(), 0L).intValue()
                ))
                .toList();
    }

    public List<FakeBybitOrderResponse> getActiveOrders() {
        return gateway.activeSimulatorOrders().stream()
                .map(this::toOrderResponse)
                .toList();
    }

    public FakeBybitOrderResponse createOrder(String bybitAdId, java.math.BigDecimal amountRub) {
        return toOrderResponse(gateway.createSimulatorOrder(bybitAdId, amountRub));
    }

    public FakeBybitOrderResponse getOrder(String bybitOrderId) {
        return gateway.findSimulatorOrder(bybitOrderId)
                .map(this::toOrderResponse)
                .orElseThrow(() -> new EntityNotFoundException("Fake Bybit order not found: " + bybitOrderId));
    }

    public FakeBybitOrderResponse sendMessage(String bybitOrderId, String message) {
        return toOrderResponse(gateway.sendCounterpartyMessage(bybitOrderId, message));
    }

    public FakeBybitOrderResponse markPaid(String bybitOrderId) {
        return toOrderResponse(gateway.markSimulatorOrderPaid(bybitOrderId));
    }

    public FakeBybitOrderResponse cancel(String bybitOrderId) {
        return toOrderResponse(gateway.cancelSimulatorOrder(bybitOrderId));
    }

    public void reset() {
        gateway.resetSimulator();
    }

    private Map<String, WorkspaceEntity> workspacesByAdId() {
        return workspaceRepository.findByEnabledTrueAndDeletedAtIsNullOrderByCreatedAtAscIdAsc().stream()
                .filter(workspace -> StringUtils.hasText(workspace.getBybitP2pAdId()))
                .collect(Collectors.toMap(
                        WorkspaceEntity::getBybitP2pAdId,
                        Function.identity(),
                        (left, right) -> left
                ));
    }

    private FakeBybitAdResponse toAdResponse(SimulatedAd ad, WorkspaceEntity workspace, int activeOrderCount) {
        return new FakeBybitAdResponse(
                ad.bybitAdId(),
                workspace == null ? null : workspace.getPublicId(),
                workspace == null ? null : workspace.getName(),
                ad.published(),
                ad.rate(),
                ad.minRub(),
                ad.maxRub(),
                ad.quantityUsdt(),
                ad.description(),
                activeOrderCount,
                ad.updatedAt()
        );
    }

    private FakeBybitOrderResponse toOrderResponse(SimulatedOrder order) {
        return new FakeBybitOrderResponse(
                order.bybitOrderId(),
                order.bybitAdId(),
                order.amountRub(),
                order.status(),
                FakeBybitGateway.statusTitle(order.status()),
                order.quantityUsdt(),
                order.feeUsdt(),
                order.createdAt(),
                order.updatedAt(),
                order.messages().stream()
                        .map(this::toMessageResponse)
                        .toList()
        );
    }

    private FakeBybitChatMessageResponse toMessageResponse(SimulatedChatMessage message) {
        boolean counterparty = "user".equalsIgnoreCase(message.roleType());
        return new FakeBybitChatMessageResponse(
                message.id(),
                message.message(),
                counterparty ? "OUTGOING" : "INCOMING",
                counterparty ? "Вы" : "FlowPay",
                "str",
                message.createdAt()
        );
    }
}
