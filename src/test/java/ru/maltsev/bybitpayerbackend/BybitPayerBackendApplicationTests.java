package ru.maltsev.bybitpayerbackend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import ru.maltsev.bybitpayerbackend.bybit.gateway.TestBybitGatewayConfiguration;

@SpringBootTest
@Import(TestBybitGatewayConfiguration.class)
class BybitPayerBackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
