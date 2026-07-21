package ru.maltsev.bybitpayerbackend.system.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;

@Component
@Order(1)
@Slf4j
public class StartupRecoveryService implements ApplicationRunner {

    private final AdvertisementManager advertisementManager;

    public StartupRecoveryService(AdvertisementManager advertisementManager) {
        this.advertisementManager = advertisementManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            advertisementManager.rebuildPublication();
            log.info("Startup publication recovery completed");
        } catch (Exception exception) {
            log.warn("Startup publication recovery failed: {}", exception.getMessage());
        }
    }
}
