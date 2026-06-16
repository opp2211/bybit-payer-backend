package ru.maltsev.bybitpayerbackend.system.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import ru.maltsev.bybitpayerbackend.bybit.gateway.TestBybitGatewayConfiguration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestBybitGatewayConfiguration.class)
class SystemControllerTest {

    @Autowired
    private SystemController systemController;

    @Test
    void getStatus() {
        systemController.getStatus();
    }
}
