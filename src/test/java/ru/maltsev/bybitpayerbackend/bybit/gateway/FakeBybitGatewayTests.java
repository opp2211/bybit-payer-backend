package ru.maltsev.bybitpayerbackend.bybit.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;

class FakeBybitGatewayTests {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    private final FakeBybitGateway gateway = new FakeBybitGateway(
            Clock.fixed(NOW, ZoneOffset.UTC),
            new BusinessProperties()
    );

    @Test
    void storesManagedAdAndCreatesActiveOrderFromSimulator() {
        gateway.updateManagedAd(adCommand());

        FakeBybitGateway.SimulatedOrder order = gateway.createSimulatorOrder(
                "ad-1",
                new BigDecimal("2500")
        );

        assertThat(gateway.publishedAds()).hasSize(1);
        assertThat(gateway.fetchActiveOrders())
                .singleElement()
                .satisfies(activeOrder -> {
                    assertThat(activeOrder.bybitOrderId()).isEqualTo(order.bybitOrderId());
                    assertThat(activeOrder.amountRub()).isEqualByComparingTo("2500.00");
                    assertThat(activeOrder.status()).isEqualTo("10");
                    assertThat(activeOrder.quantityUsdt()).isEqualByComparingTo("25.0000");
                    assertThat(activeOrder.feeUsdt()).isEqualByComparingTo("0.0000");
                });
        assertThat(gateway.fetchOrder(order.bybitOrderId()))
                .isPresent()
                .get()
                .extracting(BybitP2pOrder::status)
                .isEqualTo("10");
    }

    @Test
    void returnsChatMessagesFromBothSides() {
        gateway.updateManagedAd(adCommand());
        FakeBybitGateway.SimulatedOrder order = gateway.createSimulatorOrder(
                "ad-1",
                new BigDecimal("2500")
        );

        gateway.sendCounterpartyMessage(order.bybitOrderId(), "Здравствуйте");
        gateway.sendChatMessage(order.bybitOrderId(), "operator-uuid", "Привет");

        assertThat(gateway.fetchChatMessages(order.bybitOrderId()))
                .extracting(BybitChatMessage::message, BybitChatMessage::roleType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Здравствуйте", "user"),
                        org.assertj.core.groups.Tuple.tuple("Привет", "merchant")
                );
    }

    @Test
    void exposesPaidCancelledAndReleasedStatusesThroughGatewayInterface() {
        gateway.updateManagedAd(adCommand());
        FakeBybitGateway.SimulatedOrder paidOrder = gateway.createSimulatorOrder(
                "ad-1",
                new BigDecimal("2500")
        );
        FakeBybitGateway.SimulatedOrder cancelledOrder = gateway.createSimulatorOrder(
                "ad-1",
                new BigDecimal("3000")
        );
        FakeBybitGateway.SimulatedOrder releasedOrder = gateway.createSimulatorOrder(
                "ad-1",
                new BigDecimal("3500")
        );

        gateway.markSimulatorOrderPaid(paidOrder.bybitOrderId());
        gateway.cancelSimulatorOrder(cancelledOrder.bybitOrderId());
        gateway.releaseOrder(releasedOrder.bybitOrderId());

        assertThat(gateway.fetchOrder(paidOrder.bybitOrderId()).orElseThrow().status()).isEqualTo("20");
        assertThat(gateway.fetchOrder(cancelledOrder.bybitOrderId()).orElseThrow().status()).isEqualTo("40");
        assertThat(gateway.fetchOrder(releasedOrder.bybitOrderId()).orElseThrow().status()).isEqualTo("50");
        assertThat(gateway.fetchActiveOrders())
                .extracting(BybitP2pOrder::bybitOrderId)
                .containsExactly(paidOrder.bybitOrderId());
    }

    @Test
    void rejectsOrderOutsideAdRange() {
        gateway.updateManagedAd(adCommand());

        assertThatThrownBy(() -> gateway.createSimulatorOrder("ad-1", new BigDecimal("999.99")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Order amount must be inside fake ad range");
    }

    private AdUpdateCommand adCommand() {
        return new AdUpdateCommand(
                "ad-1",
                true,
                new BigDecimal("100"),
                new BigDecimal("1000"),
                new BigDecimal("10000"),
                new BigDecimal("100"),
                "Заходите только на сумму 2500 руб."
        );
    }
}
