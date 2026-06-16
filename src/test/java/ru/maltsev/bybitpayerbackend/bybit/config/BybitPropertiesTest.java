package ru.maltsev.bybitpayerbackend.bybit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import ru.maltsev.bybitpayerbackend.BybitPayerBackendApplication;
import ru.maltsev.bybitpayerbackend.bybit.gateway.TestBybitGatewayConfiguration;

@SpringBootTest(classes = {BybitPayerBackendApplication.class})
@Import(TestBybitGatewayConfiguration.class)
class BybitPropertiesTest {

    @Autowired
    private BybitProperties bybitProperties;

    @Test
    void getBaseUrl() {
        String baseUrl = bybitProperties.getBaseUrl();
        assertEquals("https://api.bybit.com", baseUrl);
    }
}
