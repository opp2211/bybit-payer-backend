package ru.maltsev.bybitpayerbackend.system.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
        BigDecimal availableRubBalance = calculateRubBalance(
                currentBybitStatus.availableUsdtBalance(),
                adState.getReferenceRate7()
        );

        return new SystemStatusResponse(
                currentBybitStatus.available(),
                currentBybitStatus.mode(),
                isGmailConfigured(),
                adState.getBybitAdId(),
                adState.isPublished(),
                adState.getLastRate(),
                adState.getLastRateSourcePosition(),
                adState.getReferenceRate7(),
                adState.getReferenceRate7WithFee(),
                adState.getReferenceRate15(),
                adState.getLastMinRub(),
                adState.getLastMaxRub(),
                adState.getLastQuantityUsdt(),
                adState.getLastDescription(),
                currentBybitStatus.availableUsdtBalance(),
                availableRubBalance,
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
            BybitStatusSnapshot current = new BybitStatusSnapshot(
                    readiness.available(),
                    readiness.mode(),
                    availableUsdt,
                    readiness.available() ? null : readiness.message(),
                    checkedAt
            );
            bybitStatus.set(current);
            logStatusChange(previous, current);
        } catch (Exception exception) {
            BybitStatusSnapshot current = new BybitStatusSnapshot(
                    false,
                    previous.mode(),
                    previous.availableUsdtBalance(),
                    exception.getMessage(),
                    checkedAt
            );
            bybitStatus.set(current);
            logStatusChange(previous, current);
        }
    }

    @Transactional
    public SystemStatusResponse resync() {
        advertisementManager.rebuildPublication();
        log.info("System resync completed");
        return getStatus();
    }

    private boolean isGmailConfigured() {
        return mailProperties.isEnabled()
                && StringUtils.hasText(mailProperties.getUsername())
                && StringUtils.hasText(mailProperties.getPassword());
    }

    private BigDecimal calculateRubBalance(BigDecimal availableUsdt, BigDecimal rateWithFee) {
        if (availableUsdt == null || rateWithFee == null) {
            return null;
        }
        return availableUsdt.multiply(rateWithFee).setScale(2, RoundingMode.HALF_UP);
    }

    private void logStatusChange(BybitStatusSnapshot previous, BybitStatusSnapshot current) {
        boolean firstCheck = previous.checkedAt() == null;
        boolean availabilityChanged = previous.available() != current.available();
        boolean detailsChanged = !Objects.equals(previous.mode(), current.mode())
                || !Objects.equals(previous.lastError(), current.lastError());
        if (!firstCheck && !availabilityChanged && !detailsChanged) {
            return;
        }

        if (current.available()) {
            log.info(
                    "Bybit status is available: mode={}, balanceUsdt={}",
                    current.mode(),
                    current.availableUsdtBalance()
            );
        } else {
            log.warn(
                    "Bybit status is unavailable: mode={}, message={}",
                    current.mode(),
                    current.lastError()
            );
        }
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
