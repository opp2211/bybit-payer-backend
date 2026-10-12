package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitManagedAdStateRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdUnpublishRecoveryService {

    private final WorkspaceRepository workspaceRepository;
    private final WithdrawalRequestRepository withdrawalRepository;
    private final BybitManagedAdStateRepository adStateRepository;
    private final AdUnpublishStateService stateService;
    private final AdvertisementManager advertisementManager;
    private final BybitGateway bybitGateway;
    private final BybitCredentialsContext credentialsContext;
    private final WorkspaceSecretService secretService;
    private final Clock clock;
    private final ConcurrentMap<Long, Instant> lastAuditAt = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${bybit.ad-unpublish-retry-interval:10s}")
    public void retryUnpublishedAds() {
        for (WorkspaceEntity workspace : workspaceRepository.findByEnabledTrueAndDeletedAtIsNullOrderByCreatedAtAscIdAsc()) {
            try {
                advertisementManager.withPublicationLock(workspace, () -> retry(workspace));
            } catch (RuntimeException exception) {
                log.error("Managed Bybit ad recovery failed: workspace={}", workspace.getPublicId(), exception);
            }
        }
    }

    private void retry(WorkspaceEntity workspace) {
        BybitManagedAdStateEntity state = adStateRepository.findByWorkspace(workspace).orElse(null);
        if (state == null || !StringUtils.hasText(state.getBybitAdId())) {
            return;
        }
        if (!withdrawalRepository.findByWorkspaceAndStatusOrderByCreatedAtAscIdAsc(workspace, WithdrawalStatus.IN_WORK).isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        Instant lastAudit = lastAuditAt.get(workspace.getId());
        boolean auditDue = lastAudit == null || !now.isBefore(lastAudit.plus(Duration.ofSeconds(30)));
        if (!auditDue && !state.isPublished() && !state.isUnpublishPending()) {
            return;
        }
        Instant nextAttempt = state.getUnpublishNextAttemptAt();
        if (nextAttempt != null && now.isBefore(nextAttempt)) {
            return;
        }

        lastAuditAt.put(workspace.getId(), now);
        Long stateId = state.getId();
        boolean pending = state.isPublished() || state.isUnpublishPending();
        if (pending) {
            stateService.markPending(stateId);
        }
        boolean remotePublished = false;
        try {
            remotePublished = credentialsContext.callWith(
                    secretService.bybitCredentials(workspace),
                    () -> bybitGateway.isManagedAdPublished(state.getBybitAdId())
            );
            if (remotePublished) {
                if (!pending) {
                    stateService.markPending(stateId);
                }
                credentialsContext.runWith(secretService.bybitCredentials(workspace), () -> {
                    bybitGateway.unpublishManagedAd(state.getBybitAdId());
                    if (bybitGateway.isManagedAdPublished(state.getBybitAdId())) {
                        throw new IllegalStateException("Bybit ad is still published after cancel: " + state.getBybitAdId());
                    }
                });
            }
            if (pending || remotePublished) {
                stateService.markUnpublished(stateId);
                log.info("Managed Bybit ad unpublication confirmed: workspace={}, adId={}",
                        workspace.getPublicId(), state.getBybitAdId());
            }
        } catch (RuntimeException exception) {
            if (pending || remotePublished) {
                stateService.markFailed(stateId, exception);
                log.error("Managed Bybit ad unpublication pending: workspace={}, adId={}, message={}",
                        workspace.getPublicId(), state.getBybitAdId(), exception.getMessage(), exception);
            } else {
                log.warn("Managed Bybit ad status audit failed: workspace={}, adId={}, message={}",
                        workspace.getPublicId(), state.getBybitAdId(), exception.getMessage());
            }
        }
    }
}
