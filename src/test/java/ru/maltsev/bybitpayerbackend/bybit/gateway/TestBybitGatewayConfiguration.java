package ru.maltsev.bybitpayerbackend.bybit.gateway;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestBybitGatewayConfiguration {

    @Bean
    @Primary
    BybitGateway bybitGateway() {
        BybitGateway gateway = mock(BybitGateway.class);
        when(gateway.checkReadiness()).thenReturn(new BybitReadiness(
                false,
                "CONFIG_MISSING",
                "Bybit API key, secret, base URL or managed ad id is not configured",
                new BigDecimal("100000")
        ));
        when(gateway.fetchReferenceRate()).thenReturn(new BigDecimal("92.31"));
        when(gateway.fetchReferenceRate(anyInt())).thenReturn(new BigDecimal("92.31"));
        when(gateway.fetchAvailableUsdtBalance()).thenReturn(new BigDecimal("100000"));
        when(gateway.fetchActiveOrders()).thenReturn(List.of());
        when(gateway.fetchOrder(anyString())).thenReturn(Optional.empty());
        when(gateway.fetchAccountInfo()).thenReturn(new BybitAccountInfo("seller-user", "seller-account", "Seller"));
        when(gateway.fetchChatMessages(anyString())).thenReturn(List.of());
        return gateway;
    }
}
