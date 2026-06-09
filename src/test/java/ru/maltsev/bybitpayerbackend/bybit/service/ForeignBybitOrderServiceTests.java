package ru.maltsev.bybitpayerbackend.bybit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.bybit.entity.ForeignBybitOrderEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitP2pOrder;
import ru.maltsev.bybitpayerbackend.bybit.repository.ForeignBybitOrderRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalMapper;

class ForeignBybitOrderServiceTests {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    @Test
    void refreshesTrackedOrderWithoutChangingItsReason() {
        ForeignBybitOrderRepository repository = mock(ForeignBybitOrderRepository.class);
        ForeignBybitOrderEntity entity = foreignOrder("order-1", "No matching withdrawal");
        when(repository.findByBybitOrderId("order-1")).thenReturn(Optional.of(entity));
        ForeignBybitOrderService service = service(repository);

        boolean tracked = service.refreshIfTracked(order("order-1", "20"));

        assertThat(tracked).isTrue();
        assertThat(entity.getBybitStatus()).isEqualTo("20");
        assertThat(entity.getReason()).isEqualTo("No matching withdrawal");
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        verify(repository).save(entity);
    }

    @Test
    void removesOrdersMissingFromActiveBybitList() {
        ForeignBybitOrderRepository repository = mock(ForeignBybitOrderRepository.class);
        ForeignBybitOrderEntity active = foreignOrder("order-1", "reason");
        ForeignBybitOrderEntity closed = foreignOrder("order-2", "reason");
        when(repository.findAllByOrderByUpdatedAtDescIdDesc()).thenReturn(List.of(active, closed));
        ForeignBybitOrderService service = service(repository);

        service.removeMissingOrders(Set.of("order-1"));

        verify(repository).deleteAll(List.of(closed));
    }

    private ForeignBybitOrderService service(ForeignBybitOrderRepository repository) {
        return new ForeignBybitOrderService(
                repository,
                mock(WithdrawalMapper.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ForeignBybitOrderEntity foreignOrder(String orderId, String reason) {
        ForeignBybitOrderEntity entity = new ForeignBybitOrderEntity();
        entity.setBybitOrderId(orderId);
        entity.setAmountRub(new BigDecimal("10000"));
        entity.setBybitStatus("10");
        entity.setReason(reason);
        entity.setCreatedAt(NOW.minusSeconds(60));
        entity.setUpdatedAt(NOW.minusSeconds(60));
        return entity;
    }

    private BybitP2pOrder order(String orderId, String status) {
        return new BybitP2pOrder(
                orderId,
                new BigDecimal("10000"),
                status,
                new BigDecimal("108.25"),
                new BigDecimal("0.30")
        );
    }
}
