package ru.maltsev.bybitpayerbackend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.FakeBybitGateway;
import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;

@SpringBootTest
@ActiveProfiles("local")
class LocalProfileConfigurationTest {

    @Autowired
    private BybitGateway bybitGateway;

    @Autowired
    private ReceiptMailProperties mailProperties;

    @Test
    void localProfileUsesFakeBybitGatewayAndDisablesMailPolling() {
        assertThat(bybitGateway).isInstanceOf(FakeBybitGateway.class);
        assertThat(bybitGateway.checkReadiness().mode()).isEqualTo("FAKE");
        assertThat(mailProperties.isEnabled()).isFalse();
    }
}
