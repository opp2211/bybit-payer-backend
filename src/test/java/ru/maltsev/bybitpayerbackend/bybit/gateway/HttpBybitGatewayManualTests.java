package ru.maltsev.bybitpayerbackend.bybit.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;

@EnabledIfSystemProperty(named = "bybit.manual.cancel-order-test", matches = "true")
class HttpBybitGatewayManualTests {

    // Hardcode a real Bybit P2P order id here before running this manual test.
    private static final String BYBIT_ORDER_ID = "";

    @Test
    void cancelsHardcodedOrder() {
        assertThat(BYBIT_ORDER_ID)
                .as("Hardcode BYBIT_ORDER_ID before running the manual cancellation test")
                .isNotBlank();

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ManualBybitGatewayConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.config.import=optional:file:./.env[.properties]",
                        "spring.main.banner-mode=off",
                        "bybit.api-key=${BYBIT_API_KEY:}",
                        "bybit.api-secret=${BYBIT_API_SECRET:}",
                        "bybit.env=${BYBIT_ENV:testnet}",
                        "bybit.base-url=${BYBIT_BASE_URL:https://api.bybit.com}",
                        "bybit.recv-window-ms=${BYBIT_RECV_WINDOW_MS:5000}",
                        "bybit.p2p-ad-id=${BYBIT_P2P_AD_ID:}",
                        "bybit.rate-limit-requests-per-second=${BYBIT_RATE_LIMIT_REQUESTS_PER_SECOND:10}",
                        "bybit.retry-max-attempts=${BYBIT_RETRY_MAX_ATTEMPTS:3}",
                        "bybit.retry-backoff-seconds=${BYBIT_RETRY_BACKOFF_SECONDS:1,2,4}"
                )
                .run()) {
            BybitProperties properties = context.getBean(BybitProperties.class);
            assertThat(properties.getApiKey()).as("Set BYBIT_API_KEY in .env/environment").isNotBlank();
            assertThat(properties.getApiSecret()).as("Set BYBIT_API_SECRET in .env/environment").isNotBlank();
            assertThat(properties.getP2pAdId()).as("Set BYBIT_P2P_AD_ID in .env/environment").isNotBlank();

            context.getBean(BybitGateway.class).cancelOrder(BYBIT_ORDER_ID);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BybitProperties.class)
    static class ManualBybitGatewayConfiguration {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        BybitGateway bybitGateway(BybitProperties properties, Clock clock) {
            return new HttpBybitGateway(properties, clock);
        }
    }
}
