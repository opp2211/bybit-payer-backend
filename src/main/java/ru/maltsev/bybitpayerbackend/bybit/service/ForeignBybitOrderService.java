package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bybit.dto.ForeignBybitOrderResponse;
import ru.maltsev.bybitpayerbackend.bybit.entity.ForeignBybitOrderEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.repository.ForeignBybitOrderRepository;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalMapper;

@Service
public class ForeignBybitOrderService {

    private final ForeignBybitOrderRepository repository;
    private final BybitGateway bybitGateway;
    private final WithdrawalMapper mapper;
    private final Clock clock;

    public ForeignBybitOrderService(
            ForeignBybitOrderRepository repository,
            BybitGateway bybitGateway,
            WithdrawalMapper mapper,
            Clock clock
    ) {
        this.repository = repository;
        this.bybitGateway = bybitGateway;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public ForeignBybitOrderEntity upsert(BybitP2pOrder order, String reason) {
        Instant now = Instant.now(clock);
        ForeignBybitOrderEntity entity = repository.findByBybitOrderId(order.bybitOrderId())
                .orElseGet(() -> newForeignOrder(order, now));
        entity.setAmountRub(order.amountRub());
        entity.setBybitStatus(order.status());
        entity.setReason(reason);
        entity.setAttentionRequired(true);
        entity.setUpdatedAt(now);

        if (order.paid()) {
            requestCancel(entity, now);
        }
        return repository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<ForeignBybitOrderResponse> getActive() {
        return repository.findAllByOrderByUpdatedAtDescIdDesc().stream()
                .map(mapper::toForeignOrderResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ForeignBybitOrderResponse getDetails(Long id) {
        return repository.findById(id)
                .map(mapper::toForeignOrderResponse)
                .orElseThrow(() -> new EntityNotFoundException("Foreign Bybit order not found: " + id));
    }

    private ForeignBybitOrderEntity newForeignOrder(BybitP2pOrder order, Instant now) {
        ForeignBybitOrderEntity entity = new ForeignBybitOrderEntity();
        entity.setBybitOrderId(order.bybitOrderId());
        entity.setAmountRub(order.amountRub());
        entity.setBybitStatus(order.status());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setAttentionRequired(true);
        return entity;
    }

    private void requestCancel(ForeignBybitOrderEntity entity, Instant now) {
        try {
            bybitGateway.requestCancel(entity.getBybitOrderId());
            entity.setCancelRequested(true);
            entity.setCancelRequestedAt(now);
            entity.setCancelRequestAttempts(entity.getCancelRequestAttempts() + 1);
            entity.setLastError(null);
        } catch (Exception exception) {
            entity.setCancelRequestAttempts(entity.getCancelRequestAttempts() + 1);
            entity.setLastError(exception.getMessage());
        }
    }
}
