package ru.maltsev.bybitpayerbackend.system.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitReadiness;
import ru.maltsev.bybitpayerbackend.bybit.service.AdvertisementManager;
import ru.maltsev.bybitpayerbackend.receipt.config.ReceiptMailProperties;
import ru.maltsev.bybitpayerbackend.security.service.CurrentUserService;
import ru.maltsev.bybitpayerbackend.system.dto.SystemStatusResponse;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceAccessService;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;

@Service
@Slf4j
public class SystemStatusService {

    private final AdvertisementManager advertisementManager;
    private final BybitGateway bybitGateway;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final WorkspaceSecretService workspaceSecretService;
    private final WorkspaceRepository workspaceRepository;
    private final CurrentUserService currentUserService;
    private final WorkspaceAccessService workspaceAccessService;
    private final ReceiptMailProperties legacyMailProperties;
    private final Clock clock;
    private final ConcurrentMap<Long, BybitStatusSnapshot> bybitStatuses = new ConcurrentHashMap<>();

    @Autowired
    public SystemStatusService(
            AdvertisementManager advertisementManager,
            BybitGateway bybitGateway,
            BybitCredentialsContext bybitCredentialsContext,
            WorkspaceSecretService workspaceSecretService,
            WorkspaceRepository workspaceRepository,
            CurrentUserService currentUserService,
            WorkspaceAccessService workspaceAccessService,
            Clock clock
    ) {
        this.advertisementManager = advertisementManager;
        this.bybitGateway = bybitGateway;
        this.bybitCredentialsContext = bybitCredentialsContext;
        this.workspaceSecretService = workspaceSecretService;
        this.workspaceRepository = workspaceRepository;
        this.currentUserService = currentUserService;
        this.workspaceAccessService = workspaceAccessService;
        this.legacyMailProperties = null;
        this.clock = clock;
    }

    public SystemStatusService(
            AdvertisementManager advertisementManager,
            BybitGateway bybitGateway,
            ReceiptMailProperties mailProperties,
            Clock clock
    ) {
        this.advertisementManager = advertisementManager;
        this.bybitGateway = bybitGateway;
        this.bybitCredentialsContext = new BybitCredentialsContext();
        this.workspaceSecretService = null;
        this.workspaceRepository = null;
        this.currentUserService = null;
        this.workspaceAccessService = null;
        this.legacyMailProperties = mailProperties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SystemStatusResponse getStatus() {
        BybitStatusSnapshot currentBybitStatus = bybitStatuses.getOrDefault(0L, BybitStatusSnapshot.notChecked());
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
                isLegacyMailConfigured(),
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

    @Transactional(readOnly = true)
    public SystemStatusResponse getStatus(String workspacePublicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        return getStatus(workspace);
    }

    private SystemStatusResponse getStatus(WorkspaceEntity workspace) {
        BybitStatusSnapshot currentBybitStatus = bybitStatuses.getOrDefault(
                workspace.getId(),
                BybitStatusSnapshot.notChecked()
        );
        BybitManagedAdStateEntity adState = advertisementManager.getCurrentState(workspace);
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
                isMailConfigured(workspace),
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
        if (workspaceRepository == null) {
            refreshLegacyBybitStatus();
            return;
        }
        for (WorkspaceEntity workspace : workspaceRepository.findByEnabledTrueAndDeletedAtIsNullOrderByCreatedAtAscIdAsc()) {
            refreshBybitStatus(workspace);
        }
    }

    private void refreshLegacyBybitStatus() {
        BybitStatusSnapshot previous = bybitStatuses.getOrDefault(0L, BybitStatusSnapshot.notChecked());
        Instant checkedAt = Instant.now(clock);
        try {
            BybitReadiness readiness = bybitGateway.checkReadiness();
            BigDecimal availableUsdt = readiness.availableUsdtBalance() == null
                    ? previous.availableUsdtBalance()
                    : readiness.availableUsdtBalance();
            bybitStatuses.put(0L, new BybitStatusSnapshot(
                    readiness.available(),
                    readiness.mode(),
                    availableUsdt,
                    readiness.available() ? null : readiness.message(),
                    checkedAt
            ));
        } catch (Exception exception) {
            bybitStatuses.put(0L, new BybitStatusSnapshot(
                    false,
                    previous.mode(),
                    previous.availableUsdtBalance(),
                    exception.getMessage(),
                    checkedAt
            ));
        }
    }

    private void refreshBybitStatus(WorkspaceEntity workspace) {
        BybitStatusSnapshot previous = bybitStatuses.getOrDefault(workspace.getId(), BybitStatusSnapshot.notChecked());
        Instant checkedAt = Instant.now(clock);
        try {
            BybitReadiness readiness = bybitCredentialsContext.callWith(
                    workspaceSecretService.bybitCredentials(workspace),
                    bybitGateway::checkReadiness
            );
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
            bybitStatuses.put(workspace.getId(), current);
            logStatusChange(workspace, previous, current);
        } catch (Exception exception) {
            BybitStatusSnapshot current = new BybitStatusSnapshot(
                    false,
                    previous.mode(),
                    previous.availableUsdtBalance(),
                    exception.getMessage(),
                    checkedAt
            );
            bybitStatuses.put(workspace.getId(), current);
            logStatusChange(workspace, previous, current);
        }
    }

    @Transactional
    public SystemStatusResponse resync(String workspacePublicId) {
        UserEntity currentUser = currentUserService.currentUser();
        WorkspaceEntity workspace = workspaceAccessService.getAccessibleWorkspace(workspacePublicId, currentUser);
        advertisementManager.rebuildPublication(workspace);
        refreshBybitStatus(workspace);
        log.info("System resync completed: workspace={}", workspace.getPublicId());
        return getStatus(workspace);
    }

    @Transactional
    public SystemStatusResponse resync() {
        advertisementManager.rebuildPublication();
        refreshLegacyBybitStatus();
        return getStatus();
    }

    private boolean isMailConfigured(WorkspaceEntity workspace) {
        return StringUtils.hasText(workspace.getImapHost())
                && workspace.getImapPort() != null
                && StringUtils.hasText(workspace.getImapUsername())
                && StringUtils.hasText(workspace.getImapPasswordEncrypted());
    }

    private boolean isLegacyMailConfigured() {
        return legacyMailProperties != null
                && legacyMailProperties.isEnabled()
                && StringUtils.hasText(legacyMailProperties.getUsername())
                && StringUtils.hasText(legacyMailProperties.getPassword());
    }

    private BigDecimal calculateRubBalance(BigDecimal availableUsdt, BigDecimal rateWithFee) {
        if (availableUsdt == null || rateWithFee == null) {
            return null;
        }
        return availableUsdt.multiply(rateWithFee).setScale(2, RoundingMode.HALF_UP);
    }

    private void logStatusChange(WorkspaceEntity workspace, BybitStatusSnapshot previous, BybitStatusSnapshot current) {
        boolean firstCheck = previous.checkedAt() == null;
        boolean availabilityChanged = previous.available() != current.available();
        boolean detailsChanged = !Objects.equals(previous.mode(), current.mode())
                || !Objects.equals(previous.lastError(), current.lastError());
        if (!firstCheck && !availabilityChanged && !detailsChanged) {
            return;
        }

        if (current.available()) {
            log.info(
                    "Bybit status is available: workspace={}, mode={}, balanceUsdt={}",
                    workspace.getPublicId(),
                    current.mode(),
                    current.availableUsdtBalance()
            );
        } else {
            log.warn(
                    "Bybit status is unavailable: workspace={}, mode={}, message={}",
                    workspace.getPublicId(),
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

        static BybitStatusSnapshot notChecked() {
            return new BybitStatusSnapshot(
                    false,
                    "NOT_CHECKED",
                    null,
                    "Bybit status has not been checked yet",
                    null
            );
        }
    }
}
