package ru.maltsev.bybitpayerbackend.bybit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.AdUpdateCommand;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentialsContext;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitManagedAdStateRepository;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.workspace.repository.WorkspaceRepository;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceSecretService;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AdvertisementManager {

    private static final int REFERENCE_RATE_15_POSITION = 15;
    private static final String AD_DESCRIPTION_TEMPLATE =
            "%s ___ Заходите только на сумму %s руб.  " +
                    "- другие суммы - отмена! ___ Принимаю на карту 3 лица по СБП";

    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final WithdrawalRequestRepository withdrawalRepository;
    private final BybitManagedAdStateRepository adStateRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WithdrawalEventService eventService;
    private final BybitGateway bybitGateway;
    private final BybitCredentialsContext bybitCredentialsContext;
    private final WorkspaceSecretService workspaceSecretService;
    private final BybitProperties bybitProperties;
    private final BusinessProperties businessProperties;
    private final Clock clock;

    public AdvertisementManager(
            WithdrawalRequestRepository withdrawalRepository,
            BybitManagedAdStateRepository adStateRepository,
            WithdrawalEventService eventService,
            BybitGateway bybitGateway,
            BybitProperties bybitProperties,
            BusinessProperties businessProperties,
            Clock clock
    ) {
        this(
                withdrawalRepository,
                adStateRepository,
                null,
                eventService,
                bybitGateway,
                new BybitCredentialsContext(),
                null,
                bybitProperties,
                businessProperties,
                clock
        );
    }

    @Autowired
    public AdvertisementManager(
            WithdrawalRequestRepository withdrawalRepository,
            BybitManagedAdStateRepository adStateRepository,
            WorkspaceRepository workspaceRepository,
            WithdrawalEventService eventService,
            BybitGateway bybitGateway,
            BybitCredentialsContext bybitCredentialsContext,
            WorkspaceSecretService workspaceSecretService,
            BybitProperties bybitProperties,
            BusinessProperties businessProperties,
            Clock clock
    ) {
        this.withdrawalRepository = withdrawalRepository;
        this.adStateRepository = adStateRepository;
        this.workspaceRepository = workspaceRepository;
        this.eventService = eventService;
        this.bybitGateway = bybitGateway;
        this.bybitCredentialsContext = bybitCredentialsContext;
        this.workspaceSecretService = workspaceSecretService;
        this.bybitProperties = bybitProperties;
        this.businessProperties = businessProperties;
        this.clock = clock;
    }

    @Transactional
    public void rebuildPublication() {
        if (workspaceRepository == null) {
            rebuildPublicationLegacy();
            return;
        }
        for (WorkspaceEntity workspace : workspaceRepository.findByEnabledTrueAndDeletedAtIsNullOrderByCreatedAtAscIdAsc()) {
            try {
                rebuildPublication(workspace);
            } catch (RuntimeException exception) {
                log.warn(
                        "Managed advertisement synchronization failed for workspace {}: {}",
                        workspace.getPublicId(),
                        exception.getMessage()
                );
            }
        }
    }

    @Transactional
    public void rebuildPublication(WorkspaceEntity workspace) {
        ReentrantLock lock = lockFor(workspace);
        lock.lock();
        try {
            bybitCredentialsContext.runWith(workspaceSecretService.bybitCredentials(workspace), () -> {
                Instant now = Instant.now(clock);
                List<WithdrawalRequestEntity> candidates = withdrawalRepository
                        .findByWorkspaceAndStatusInOrderByCreatedAtAscIdAsc(
                                workspace,
                                WithdrawalStatus.QUEUE_MANAGED_STATUSES
                        );

                List<WithdrawalRequestEntity> published = applyQueueRules(candidates, now);
                withdrawalRepository.saveAll(candidates);

                int initialRatePosition = initialRatePosition();
                AdvertisementSnapshot snapshot = buildSnapshot(published, initialRatePosition);
                ensureBalance(snapshot);
                persistAndPushAdState(workspace, snapshot, now, initialRatePosition);
                log.info(
                        "Managed advertisement synchronized: workspace={}, published={}, candidates={}, publishedWithdrawals={}, rate={}, quantityUsdt={}",
                        workspace.getPublicId(),
                        snapshot.published(),
                        candidates.size(),
                        published.size(),
                        snapshot.rate(),
                        snapshot.quantityUsdt()
                );
            });
        } catch (BusinessException exception) {
            log.warn(
                    "Managed advertisement synchronization rejected: message={}, details={}",
                    exception.getMessage(),
                    exception.getDetails()
            );
            throw exception;
        } catch (RuntimeException exception) {
            log.error("Managed advertisement synchronization failed: {}", exception.getMessage(), exception);
            throw exception;
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(
            initialDelayString = "${bybit.ad-rate-refresh-interval:5m}",
            fixedDelayString = "${bybit.ad-rate-refresh-interval:5m}"
    )
    @Transactional
    public void refreshPublicationRate() {
        if (workspaceRepository == null) {
            refreshPublicationRateLegacy();
            return;
        }
        for (WorkspaceEntity workspace : workspaceRepository.findByEnabledTrueAndDeletedAtIsNullOrderByCreatedAtAscIdAsc()) {
            refreshPublicationRate(workspace);
        }
    }

    private void refreshPublicationRate(WorkspaceEntity workspace) {
        ReentrantLock lock = lockFor(workspace);
        lock.lock();
        try {
            bybitCredentialsContext.runWith(workspaceSecretService.bybitCredentials(workspace), () -> {
                BybitManagedAdStateEntity currentState = adStateRepository.findByWorkspace(workspace)
                        .orElseGet(() -> newAdState(workspace));
                int targetPosition = Optional.ofNullable(currentState.getNextRateSourcePosition())
                        .orElse(initialRatePosition());
                targetPosition = Math.max(minRatePosition(), Math.min(initialRatePosition(), targetPosition));

                List<WithdrawalRequestEntity> published = withdrawalRepository
                        .findByWorkspaceAndStatusOrderByCreatedAtAscIdAsc(workspace, WithdrawalStatus.IN_WORK);
                AdvertisementSnapshot snapshot = buildSnapshot(published, targetPosition);
                ensureBalance(snapshot);
                int nextPosition = Math.max(minRatePosition(), targetPosition - 1);
                persistAndPushAdState(workspace, snapshot, Instant.now(clock), nextPosition);
                log.info(
                        "Managed advertisement rate refreshed: workspace={}, position={}, nextPosition={}, rate={}",
                        workspace.getPublicId(),
                        targetPosition,
                        nextPosition,
                        snapshot.rate()
                );
            });
        } catch (RuntimeException exception) {
            log.error(
                    "Managed advertisement rate refresh failed: workspace={}, message={}",
                    workspace.getPublicId(),
                    exception.getMessage(),
                    exception
            );
        } finally {
            lock.unlock();
        }
    }

    @Transactional(readOnly = true)
    public BybitManagedAdStateEntity getCurrentState() {
        return adStateRepository.findAll().stream()
                .findFirst()
                .orElseGet(this::newLegacyAdState);
    }

    @Transactional(readOnly = true)
    public BybitManagedAdStateEntity getCurrentState(WorkspaceEntity workspace) {
        return adStateRepository.findByWorkspace(workspace)
                .orElseGet(() -> newAdState(workspace));
    }

    private void rebuildPublicationLegacy() {
        ReentrantLock lock = locks.computeIfAbsent(0L, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Instant now = Instant.now(clock);
            List<WithdrawalRequestEntity> candidates = withdrawalRepository
                    .findByStatusInOrderByCreatedAtAscIdAsc(WithdrawalStatus.QUEUE_MANAGED_STATUSES);
            List<WithdrawalRequestEntity> published = applyQueueRules(candidates, now);
            withdrawalRepository.saveAll(candidates);
            int initialRatePosition = initialRatePosition();
            AdvertisementSnapshot snapshot = buildSnapshot(published, initialRatePosition);
            ensureBalance(snapshot);
            persistAndPushLegacyAdState(snapshot, now, initialRatePosition);
        } finally {
            lock.unlock();
        }
    }

    private void refreshPublicationRateLegacy() {
        ReentrantLock lock = locks.computeIfAbsent(0L, ignored -> new ReentrantLock());
        lock.lock();
        try {
            BybitManagedAdStateEntity currentState = adStateRepository.findAll().stream()
                    .findFirst()
                    .orElseGet(this::newLegacyAdState);
            int targetPosition = Optional.ofNullable(currentState.getNextRateSourcePosition())
                    .orElse(initialRatePosition());
            targetPosition = Math.max(minRatePosition(), Math.min(initialRatePosition(), targetPosition));
            List<WithdrawalRequestEntity> published = withdrawalRepository
                    .findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus.IN_WORK);
            AdvertisementSnapshot snapshot = buildSnapshot(published, targetPosition);
            ensureBalance(snapshot);
            int nextPosition = Math.max(minRatePosition(), targetPosition - 1);
            persistAndPushLegacyAdState(snapshot, Instant.now(clock), nextPosition);
        } catch (RuntimeException exception) {
            log.error("Managed advertisement rate refresh failed: {}", exception.getMessage(), exception);
        } finally {
            lock.unlock();
        }
    }

    private List<WithdrawalRequestEntity> applyQueueRules(List<WithdrawalRequestEntity> candidates, Instant now) {
        PayerBankType activePayerBankType = candidates.stream()
                .findFirst()
                .map(withdrawal -> PayerBankType.effective(withdrawal.getPayerBankType()))
                .orElse(null);
        Set<String> publishedAmountKeys = new HashSet<>();
        List<WithdrawalRequestEntity> published = new ArrayList<>();
        int queuedPosition = 1;

        for (WithdrawalRequestEntity withdrawal : candidates) {
            PayerBankType payerBankType = PayerBankType.effective(withdrawal.getPayerBankType());
            withdrawal.setQueueGroupKey(payerBankType.name());
            String amountKey = amountKey(withdrawal.getAmountRub());
            boolean matchesActiveGroup = payerBankType == activePayerBankType;
            boolean canPublish = matchesActiveGroup
                    && publishedAmountKeys.size() < businessProperties.getMaxPublishedAmounts()
                    && publishedAmountKeys.add(amountKey);

            if (canPublish) {
                if (withdrawal.getStatus() != WithdrawalStatus.IN_WORK) {
                    withdrawal.setStatus(WithdrawalStatus.IN_WORK);
                    if (withdrawal.getPublishedAt() == null) {
                        withdrawal.setPublishedAt(now);
                    }
                    eventService.add(withdrawal, WithdrawalEventType.WITHDRAWAL_PUBLISHED, "Withdrawal amount published in managed ad");
                }
                withdrawal.setQueuePosition(null);
                published.add(withdrawal);
            } else {
                if (withdrawal.getStatus() != WithdrawalStatus.QUEUED) {
                    withdrawal.setStatus(WithdrawalStatus.QUEUED);
                    if (withdrawal.getQueuedAt() == null) {
                        withdrawal.setQueuedAt(now);
                    }
                    eventService.add(withdrawal, WithdrawalEventType.WITHDRAWAL_QUEUED, "Withdrawal queued by amount uniqueness or publication limit");
                }
                withdrawal.setQueuePosition(queuedPosition++);
            }
        }

        return published;
    }

    private AdvertisementSnapshot buildSnapshot(
            List<WithdrawalRequestEntity> published,
            int rateSourcePosition
    ) {
        BigDecimal rate = bybitGateway.fetchReferenceRate(rateSourcePosition);
        BigDecimal referenceRate7 = rateSourcePosition == minRatePosition()
                ? rate
                : bybitGateway.fetchReferenceRate(minRatePosition());
        BigDecimal referenceRate7WithFee = referenceRate7
                .divide(
                        BigDecimal.ONE.add(businessProperties.getP2pFeeRate()),
                        8,
                        RoundingMode.HALF_UP
                );
        BigDecimal referenceRate15 = rateSourcePosition == REFERENCE_RATE_15_POSITION
                ? rate
                : bybitGateway.fetchReferenceRate(REFERENCE_RATE_15_POSITION);
        BigDecimal availableUsdt = bybitGateway.fetchAvailableUsdtBalance();
        if (published.isEmpty()) {
            return new AdvertisementSnapshot(
                    false,
                    rate,
                    rateSourcePosition,
                    referenceRate7,
                    referenceRate7WithFee,
                    referenceRate15,
                    bybitProperties.getDefaultMinRub(),
                    bybitProperties.getDefaultMaxRub(),
                    BigDecimal.ZERO.setScale(businessProperties.getUsdtQuantityScale(), RoundingMode.UNNECESSARY),
                    "",
                    availableUsdt
            );
        }

        List<BigDecimal> amounts = published.stream()
                .map(WithdrawalRequestEntity::getAmountRub)
                .distinct()
                .sorted()
                .toList();

        BigDecimal minRub = amounts.stream()
                .min(Comparator.naturalOrder())
                .orElse(bybitProperties.getDefaultMinRub())
                .min(bybitProperties.getDefaultMinRub());
        BigDecimal maxRub = amounts.stream()
                .max(Comparator.naturalOrder())
                .orElse(bybitProperties.getDefaultMaxRub())
                .max(bybitProperties.getDefaultMaxRub());
        BigDecimal quantityUsdt = maxRub.divide(rate, businessProperties.getUsdtQuantityScale(), RoundingMode.CEILING);
        String amountText = amounts.stream()
                .map(this::formatRubAmount)
                .collect(Collectors.joining(" / "));
        PayerBankType payerBankType = PayerBankType.effective(published.getFirst().getPayerBankType());
        String description = AD_DESCRIPTION_TEMPLATE.formatted(payerBankType.getAdvertisementIntro(), amountText);

        return new AdvertisementSnapshot(
                true,
                rate,
                rateSourcePosition,
                referenceRate7,
                referenceRate7WithFee,
                referenceRate15,
                minRub,
                maxRub,
                quantityUsdt,
                description,
                availableUsdt
        );
    }

    private void ensureBalance(AdvertisementSnapshot snapshot) {
        if (snapshot.quantityUsdt().compareTo(snapshot.availableUsdt()) <= 0) {
            return;
        }
        throw BusinessException.conflict(
                "Insufficient USDT balance for managed ad",
                List.of(
                        "requiredUsdt=" + snapshot.quantityUsdt(),
                        "availableUsdt=" + snapshot.availableUsdt(),
                        "rate=" + snapshot.rate(),
                        "maxRub=" + snapshot.maxRub()
                )
        );
    }

    private void persistAndPushAdState(
            WorkspaceEntity workspace,
            AdvertisementSnapshot snapshot,
            Instant now,
            int nextRateSourcePosition
    ) {
        BybitManagedAdStateEntity state = adStateRepository.findByWorkspace(workspace)
                .orElseGet(() -> newAdState(workspace));
        boolean wasPublished = state.isPublished();
        state.setWorkspace(workspace);
        state.setBybitAdId(workspace.getBybitP2pAdId());
        state.setPublished(snapshot.published());
        state.setLastRate(snapshot.rate());
        state.setLastRateSourcePosition(snapshot.rateSourcePosition());
        state.setNextRateSourcePosition(nextRateSourcePosition);
        state.setReferenceRate7(snapshot.referenceRate7());
        state.setReferenceRate7WithFee(snapshot.referenceRate7WithFee());
        state.setReferenceRate15(snapshot.referenceRate15());
        state.setLastMinRub(snapshot.minRub());
        state.setLastMaxRub(snapshot.maxRub());
        state.setLastQuantityUsdt(snapshot.quantityUsdt());
        state.setLastDescription(snapshot.description());
        state.setLastUpdatedAt(now);
        state.setLastError(null);

        AdUpdateCommand command = new AdUpdateCommand(
                state.getBybitAdId(),
                state.isPublished(),
                state.getLastRate(),
                state.getLastMinRub(),
                state.getLastMaxRub(),
                state.getLastQuantityUsdt(),
                state.getLastDescription()
        );
        if (snapshot.published()) {
            bybitGateway.updateManagedAd(command);
        } else if (wasPublished && StringUtils.hasText(state.getBybitAdId())) {
            bybitGateway.unpublishManagedAd(state.getBybitAdId());
        }

        adStateRepository.save(state);
    }

    private void persistAndPushLegacyAdState(
            AdvertisementSnapshot snapshot,
            Instant now,
            int nextRateSourcePosition
    ) {
        BybitManagedAdStateEntity state = adStateRepository.findAll().stream()
                .findFirst()
                .orElseGet(this::newLegacyAdState);
        boolean wasPublished = state.isPublished();
        state.setBybitAdId(bybitProperties.getP2pAdId());
        state.setPublished(snapshot.published());
        state.setLastRate(snapshot.rate());
        state.setLastRateSourcePosition(snapshot.rateSourcePosition());
        state.setNextRateSourcePosition(nextRateSourcePosition);
        state.setReferenceRate7(snapshot.referenceRate7());
        state.setReferenceRate7WithFee(snapshot.referenceRate7WithFee());
        state.setReferenceRate15(snapshot.referenceRate15());
        state.setLastMinRub(snapshot.minRub());
        state.setLastMaxRub(snapshot.maxRub());
        state.setLastQuantityUsdt(snapshot.quantityUsdt());
        state.setLastDescription(snapshot.description());
        state.setLastUpdatedAt(now);
        state.setLastError(null);

        AdUpdateCommand command = new AdUpdateCommand(
                state.getBybitAdId(),
                state.isPublished(),
                state.getLastRate(),
                state.getLastMinRub(),
                state.getLastMaxRub(),
                state.getLastQuantityUsdt(),
                state.getLastDescription()
        );
        if (snapshot.published()) {
            bybitGateway.updateManagedAd(command);
        } else if (wasPublished && StringUtils.hasText(state.getBybitAdId())) {
            bybitGateway.unpublishManagedAd(state.getBybitAdId());
        }

        adStateRepository.save(state);
    }

    private BybitManagedAdStateEntity newAdState(WorkspaceEntity workspace) {
        BybitManagedAdStateEntity state = new BybitManagedAdStateEntity();
        state.setWorkspace(workspace);
        state.setBybitAdId(workspace.getBybitP2pAdId());
        state.setPublished(false);
        state.setNextRateSourcePosition(initialRatePosition());
        return state;
    }

    private BybitManagedAdStateEntity newLegacyAdState() {
        BybitManagedAdStateEntity state = new BybitManagedAdStateEntity();
        state.setBybitAdId(bybitProperties.getP2pAdId());
        state.setPublished(false);
        state.setNextRateSourcePosition(initialRatePosition());
        return state;
    }

    private ReentrantLock lockFor(WorkspaceEntity workspace) {
        return locks.computeIfAbsent(workspace.getId(), ignored -> new ReentrantLock());
    }

    private int initialRatePosition() {
        return Math.max(minRatePosition(), bybitProperties.getRateSourceAdIndex());
    }

    private int minRatePosition() {
        return Math.max(1, bybitProperties.getRateSourceMinAdIndex());
    }

    private String amountKey(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private String formatRubAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
