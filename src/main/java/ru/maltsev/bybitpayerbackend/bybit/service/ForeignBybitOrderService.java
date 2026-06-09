package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bybit.dto.ForeignBybitOrderResponse;
import ru.maltsev.bybitpayerbackend.bybit.entity.ForeignBybitOrderEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.repository.ForeignBybitOrderRepository;
import ru.maltsev.bybitpayerbackend.common.exception.EntityNotFoundException;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalMapper;

@Service
@Slf4j
public class ForeignBybitOrderService {

    private final ForeignBybitOrderRepository repository;
    private final WithdrawalMapper mapper;
    private final Clock clock;

    public ForeignBybitOrderService(
            ForeignBybitOrderRepository repository,
            WithdrawalMapper mapper,
            Clock clock
    ) {
        this.repository = repository;
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
        entity.setUpdatedAt(now);

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

    @Transactional
    public boolean refreshIfTracked(BybitP2pOrder order) {
        var existing = repository.findByBybitOrderId(order.bybitOrderId());
        if (existing.isEmpty()) {
            return false;
        }

        ForeignBybitOrderEntity entity = existing.get();
        String previousStatus = entity.getBybitStatus();
        entity.setAmountRub(order.amountRub());
        entity.setBybitStatus(order.status());
        entity.setUpdatedAt(Instant.now(clock));
        repository.save(entity);
        if (!Objects.equals(previousStatus, entity.getBybitStatus())) {
            log.info(
                    "Foreign Bybit order updated: orderId={}, status={}",
                    entity.getBybitOrderId(),
                    entity.getBybitStatus()
            );
        }
        return true;
    }

    @Transactional
    public void removeMissingOrders(Set<String> activeOrderIds) {
        List<ForeignBybitOrderEntity> missingOrders = repository.findAllByOrderByUpdatedAtDescIdDesc().stream()
                .filter(entity -> !activeOrderIds.contains(entity.getBybitOrderId()))
                .toList();
        if (missingOrders.isEmpty()) {
            return;
        }

        repository.deleteAll(missingOrders);
        log.info(
                "Inactive foreign Bybit orders removed: count={}, orderIds={}",
                missingOrders.size(),
                missingOrders.stream().map(ForeignBybitOrderEntity::getBybitOrderId).toList()
        );
    }

    private ForeignBybitOrderEntity newForeignOrder(BybitP2pOrder order, Instant now) {
        ForeignBybitOrderEntity entity = new ForeignBybitOrderEntity();
        entity.setBybitOrderId(order.bybitOrderId());
        entity.setAmountRub(order.amountRub());
        entity.setBybitStatus(order.status());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }
}
