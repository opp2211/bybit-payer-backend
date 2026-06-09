package ru.maltsev.bybitpayerbackend.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitReadiness;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;
import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;
import ru.maltsev.bybitpayerbackend.system.dto.SystemStatusResponse;

class SystemStatusServiceTests {

    private static final Instant NOW = Instant.parse("2026-06-09T10:00:00Z");

    private final AdvertisementManager advertisementManager = mock(AdvertisementManager.class);
    private final BybitGateway bybitGateway = mock(BybitGateway.class);
    private final ReceiptMailProperties mailProperties = new ReceiptMailProperties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private SystemStatusService service;

    @BeforeEach
    void setUp() {
        when(advertisementManager.getCurrentState()).thenReturn(adState());
        service = new SystemStatusService(advertisementManager, bybitGateway, mailProperties, clock);
    }

    @Test
    void readsStatusWithoutCallingBybit() {
        SystemStatusResponse response = service.getStatus();

        assertThat(response.bybitApiAvailable()).isFalse();
        assertThat(response.bybitMode()).isEqualTo("NOT_CHECKED");
        assertThat(response.bybitLastCheckedAt()).isNull();
        verifyNoInteractions(bybitGateway);
    }

    @Test
    void publishesRefreshedBybitStatus() {
        when(bybitGateway.checkReadiness()).thenReturn(new BybitReadiness(
                true,
                "HTTP",
                "Bybit HTTP gateway is available",
                new BigDecimal("123.45")
        ));

        service.refreshBybitStatus();
        reset(bybitGateway);
        SystemStatusResponse response = service.getStatus();

        assertThat(response.bybitApiAvailable()).isTrue();
        assertThat(response.bybitMode()).isEqualTo("HTTP");
        assertThat(response.availableUsdtBalance()).isEqualByComparingTo("123.45");
        assertThat(response.lastSystemError()).isNull();
        assertThat(response.bybitLastCheckedAt()).isEqualTo(NOW);
        verifyNoInteractions(bybitGateway);
    }

    @Test
    void keepsLastKnownBalanceWhenRefreshFails() {
        when(bybitGateway.checkReadiness())
                .thenReturn(new BybitReadiness(true, "HTTP", "OK", new BigDecimal("123.45")))
                .thenReturn(new BybitReadiness(false, "HTTP", "Bybit unavailable", null));

        service.refreshBybitStatus();
        service.refreshBybitStatus();
        SystemStatusResponse response = service.getStatus();

        assertThat(response.bybitApiAvailable()).isFalse();
        assertThat(response.availableUsdtBalance()).isEqualByComparingTo("123.45");
        assertThat(response.lastSystemError()).isEqualTo("Bybit unavailable");
        assertThat(response.bybitLastCheckedAt()).isEqualTo(NOW);
    }

    private BybitManagedAdStateEntity adState() {
        BybitManagedAdStateEntity state = new BybitManagedAdStateEntity();
        state.setBybitAdId("ad-123");
        state.setPublished(true);
        state.setLastRate(new BigDecimal("92.31"));
        state.setLastUpdatedAt(NOW.minusSeconds(60));
        return state;
    }
}
