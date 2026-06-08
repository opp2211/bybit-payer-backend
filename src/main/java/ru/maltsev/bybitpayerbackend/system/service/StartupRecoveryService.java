package ru.maltsev.bybitpayerbackend.system.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;

@Component
public class StartupRecoveryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryService.class);

    private final AdvertisementManager advertisementManager;

    public StartupRecoveryService(AdvertisementManager advertisementManager) {
        this.advertisementManager = advertisementManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            advertisementManager.rebuildPublication();
        } catch (Exception exception) {
            log.warn("Startup publication recovery failed: {}", exception.getMessage());
        }
    }
}
