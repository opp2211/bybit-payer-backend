package ru.maltsev.bybitpayerbackend.system.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Scheduled;
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
    private final Clock clock;
    private final AtomicReference<BybitStatusSnapshot> bybitStatus = new AtomicReference<>(
            new BybitStatusSnapshot(
                    false,
                    "NOT_CHECKED",
                    null,
                    "Bybit status has not been checked yet",
                    null
            )
    );

    public SystemStatusService(
            AdvertisementManager advertisementManager,
            BybitGateway bybitGateway,
            ReceiptMailProperties mailProperties,
            Clock clock
    ) {
        this.advertisementManager = advertisementManager;
        this.bybitGateway = bybitGateway;
        this.mailProperties = mailProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SystemStatusResponse getStatus() {
        BybitStatusSnapshot currentBybitStatus = bybitStatus.get();
        BybitManagedAdStateEntity adState = advertisementManager.getCurrentState();
        String lastError = currentBybitStatus.lastError() == null
                ? adState.getLastError()
                : currentBybitStatus.lastError();

        return new SystemStatusResponse(
                currentBybitStatus.available(),
                currentBybitStatus.mode(),
                isGmailConfigured(),
                adState.getBybitAdId(),
                adState.isPublished(),
                adState.getLastRate(),
                adState.getLastMinRub(),
                adState.getLastMaxRub(),
                adState.getLastQuantityUsdt(),
                adState.getLastDescription(),
                currentBybitStatus.availableUsdtBalance(),
                lastError,
                currentBybitStatus.checkedAt(),
                adState.getLastUpdatedAt()
        );
    }

    @Scheduled(
            initialDelayString = "${system.status-initial-delay:0s}",
            fixedDelayString = "${system.status-refresh-interval:30s}"
    )
    public void refreshBybitStatus() {
        BybitStatusSnapshot previous = bybitStatus.get();
        Instant checkedAt = Instant.now(clock);
        try {
            BybitReadiness readiness = bybitGateway.checkReadiness();
            BigDecimal availableUsdt = readiness.availableUsdtBalance() == null
                    ? previous.availableUsdtBalance()
                    : readiness.availableUsdtBalance();
            bybitStatus.set(new BybitStatusSnapshot(
                    readiness.available(),
                    readiness.mode(),
                    availableUsdt,
                    readiness.available() ? null : readiness.message(),
                    checkedAt
            ));
        } catch (Exception exception) {
            bybitStatus.set(new BybitStatusSnapshot(
                    false,
                    previous.mode(),
                    previous.availableUsdtBalance(),
                    exception.getMessage(),
                    checkedAt
            ));
        }
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

    private record BybitStatusSnapshot(
            boolean available,
            String mode,
            BigDecimal availableUsdtBalance,
            String lastError,
            Instant checkedAt
    ) {
    }
}
