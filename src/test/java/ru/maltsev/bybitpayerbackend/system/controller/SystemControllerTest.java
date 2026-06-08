package ru.maltsev.bybitpayerbackend.system.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SystemControllerTest {

    @Autowired
    private SystemController systemController;

    @Test
    void getStatus() {
        systemController.getStatus();
    }
}