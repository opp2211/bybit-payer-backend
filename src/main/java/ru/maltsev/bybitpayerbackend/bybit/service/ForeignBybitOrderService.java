package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        var existing = repository.findByBybitOrderId(order.bybitOrderId());
        boolean created = existing.isEmpty();
        ForeignBybitOrderEntity entity = existing.orElseGet(() -> newForeignOrder(order, now));
        String previousStatus = entity.getBybitStatus();
        String previousReason = entity.getReason();
        entity.setAmountRub(order.amountRub());
        entity.setBybitStatus(order.status());
        entity.setReason(reason);
        entity.setAttentionRequired(true);
        entity.setUpdatedAt(now);

        if (order.paid()) {
            requestCancel(entity, now);
        }
        ForeignBybitOrderEntity saved = repository.save(entity);
        if (created) {
            log.warn(
                    "Foreign Bybit order detected: orderId={}, amountRub={}, status={}, reason={}",
                    saved.getBybitOrderId(),
                    saved.getAmountRub(),
                    saved.getBybitStatus(),
                    saved.getReason()
            );
        } else if (!Objects.equals(previousStatus, saved.getBybitStatus())
                || !Objects.equals(previousReason, saved.getReason())) {
            log.info(
                    "Foreign Bybit order updated: orderId={}, status={}, reason={}",
                    saved.getBybitOrderId(),
                    saved.getBybitStatus(),
                    saved.getReason()
            );
        }
        return saved;
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
        boolean alreadyRequested = entity.isCancelRequested();
        String previousError = entity.getLastError();
        try {
            bybitGateway.requestCancel(entity.getBybitOrderId());
            entity.setCancelRequested(true);
            entity.setCancelRequestedAt(now);
            entity.setCancelRequestAttempts(entity.getCancelRequestAttempts() + 1);
            entity.setLastError(null);
            if (!alreadyRequested) {
                log.info(
                        "Foreign Bybit order cancellation requested: orderId={}, attempt={}",
                        entity.getBybitOrderId(),
                        entity.getCancelRequestAttempts()
                );
            }
        } catch (Exception exception) {
            entity.setCancelRequestAttempts(entity.getCancelRequestAttempts() + 1);
            entity.setLastError(exception.getMessage());
            if (entity.getCancelRequestAttempts() == 1
                    || !Objects.equals(previousError, exception.getMessage())) {
                log.warn(
                        "Foreign Bybit order cancellation failed: orderId={}, attempt={}, message={}",
                        entity.getBybitOrderId(),
                        entity.getCancelRequestAttempts(),
                        exception.getMessage()
                );
            } else {
                log.debug(
                        "Foreign Bybit order cancellation still failing: orderId={}, attempt={}",
                        entity.getBybitOrderId(),
                        entity.getCancelRequestAttempts()
                );
            }
        }
    }
}
