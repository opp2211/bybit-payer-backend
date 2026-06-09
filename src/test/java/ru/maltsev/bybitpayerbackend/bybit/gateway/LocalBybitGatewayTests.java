package ru.maltsev.bybitpayerbackend.bybit.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;

class LocalBybitGatewayTests {

    @Test
    void exposesConfiguredLocalValues() {
        BybitProperties properties = new BybitProperties();
        properties.getLocal().setReferenceRate(new BigDecimal("91.25"));
        properties.getLocal().setAvailableUsdt(new BigDecimal("456.78"));
        LocalBybitGateway gateway = new LocalBybitGateway(properties);

        BybitReadiness readiness = gateway.checkReadiness();

        assertThat(readiness.available()).isTrue();
        assertThat(readiness.mode()).isEqualTo("LOCAL_NOOP");
        assertThat(readiness.availableUsdtBalance()).isEqualByComparingTo("456.78");
        assertThat(gateway.fetchReferenceRate()).isEqualByComparingTo("91.25");
        assertThat(gateway.fetchActiveOrders()).isEmpty();
    }
}
