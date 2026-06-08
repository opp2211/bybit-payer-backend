package ru.maltsev.bybitpayerbackend.system.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitReadiness;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;
import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;
import ru.maltsev.bybitpayerbackend.system.dto.SystemStatusResponse;

@Service
public class SystemStatusService {

    private final AdvertisementManager advertisementManager;
    private final BybitGateway bybitGateway;
    private final ReceiptMailProperties mailProperties;

    public SystemStatusService(
            AdvertisementManager advertisementManager,
            BybitGateway bybitGateway,
            ReceiptMailProperties mailProperties
    ) {
        this.advertisementManager = advertisementManager;
        this.bybitGateway = bybitGateway;
        this.mailProperties = mailProperties;
    }

    @Transactional(readOnly = true)
    public SystemStatusResponse getStatus() {
        BybitReadiness readiness = bybitGateway.checkReadiness();
        BybitManagedAdStateEntity adState = advertisementManager.getCurrentState();
        BigDecimal availableUsdt = null;
        String lastError = adState.getLastError();
        try {
            availableUsdt = bybitGateway.fetchAvailableUsdtBalance();
        } catch (Exception exception) {
            lastError = exception.getMessage();
        }

        return new SystemStatusResponse(
                readiness.available(),
                readiness.mode(),
                isGmailConfigured(),
                adState.getBybitAdId(),
                adState.isPublished(),
                adState.getLastRate(),
                adState.getLastMinRub(),
                adState.getLastMaxRub(),
                adState.getLastQuantityUsdt(),
                adState.getLastDescription(),
                availableUsdt,
                lastError,
                adState.getLastUpdatedAt()
        );
    }

    @Transactional
    public SystemStatusResponse resync() {
        advertisementManager.rebuildPublication();
        return getStatus();
    }

    private boolean isGmailConfigured() {
        return mailProperties.isEnabled()
                && StringUtils.hasText(mailProperties.getUsername())
                && StringUtils.hasText(mailProperties.getPassword());
    }
}
