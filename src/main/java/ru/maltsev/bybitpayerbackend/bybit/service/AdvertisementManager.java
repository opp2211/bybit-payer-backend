package ru.maltsev.bybitpayerbackend.bybit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.gateway.AdUpdateCommand;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitGateway;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitManagedAdStateRepository;
import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;
import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.service.WithdrawalEventService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AdvertisementManager {

    private static final int REFERENCE_RATE_15_POSITION = 15;
    private static final String AD_DESCRIPTION_TEMPLATE =
            "Только Т-банк! ___ Заходите только на сумму %s руб.  " +
                    "- другие суммы - отмена! ___ Принимаю на карту" +
                    " 3 лица по СБП ___ Понадобится чек с офф. почты" +
                    " банка мне на почту";

    private final ReentrantLock lock = new ReentrantLock();
    private final WithdrawalRequestRepository withdrawalRepository;
    private final BybitManagedAdStateRepository adStateRepository;
    private final WithdrawalEventService eventService;
    private final BybitGateway bybitGateway;
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
        this.withdrawalRepository = withdrawalRepository;
        this.adStateRepository = adStateRepository;
        this.eventService = eventService;
        this.bybitGateway = bybitGateway;
        this.bybitProperties = bybitProperties;
        this.businessProperties = businessProperties;
        this.clock = clock;
    }

    @Transactional
    public void rebuildPublication() {
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
            persistAndPushAdState(snapshot, now, initialRatePosition);
            log.info(
                    "Managed advertisement synchronized: published={}, candidates={}, publishedWithdrawals={}, rate={}, quantityUsdt={}",
                    snapshot.published(),
                    candidates.size(),
                    published.size(),
                    snapshot.rate(),
                    snapshot.quantityUsdt()
            );
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
        lock.lock();
        try {
            BybitManagedAdStateEntity currentState = adStateRepository.findAll().stream()
                    .findFirst()
                    .orElseGet(this::newAdState);
            int targetPosition = Optional.ofNullable(currentState.getNextRateSourcePosition())
                    .orElse(initialRatePosition());
            targetPosition = Math.max(minRatePosition(), Math.min(initialRatePosition(), targetPosition));

            List<WithdrawalRequestEntity> published = withdrawalRepository
                    .findByStatusOrderByCreatedAtAscIdAsc(WithdrawalStatus.IN_WORK);
            AdvertisementSnapshot snapshot = buildSnapshot(published, targetPosition);
            ensureBalance(snapshot);
            int nextPosition = Math.max(minRatePosition(), targetPosition - 1);
            persistAndPushAdState(snapshot, Instant.now(clock), nextPosition);
            log.info(
                    "Managed advertisement rate refreshed: position={}, nextPosition={}, rate={}",
                    targetPosition,
                    nextPosition,
                    snapshot.rate()
            );
        } catch (RuntimeException exception) {
            log.error("Managed advertisement rate refresh failed: {}", exception.getMessage(), exception);
        } finally {
            lock.unlock();
        }
    }

    @Transactional(readOnly = true)
    public BybitManagedAdStateEntity getCurrentState() {
        return adStateRepository.findAll().stream()
                .findFirst()
                .orElseGet(this::newAdState);
    }

    private List<WithdrawalRequestEntity> applyQueueRules(List<WithdrawalRequestEntity> candidates, Instant now) {
        Set<String> publishedAmountKeys = new HashSet<>();
        List<WithdrawalRequestEntity> published = new ArrayList<>();
        int queuedPosition = 1;

        for (WithdrawalRequestEntity withdrawal : candidates) {
            String amountKey = amountKey(withdrawal.getAmountRub());
            boolean canPublish = publishedAmountKeys.size() < businessProperties.getMaxPublishedAmounts()
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
        String description = AD_DESCRIPTION_TEMPLATE.formatted(amountText);

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
            AdvertisementSnapshot snapshot,
            Instant now,
            int nextRateSourcePosition
    ) {
        BybitManagedAdStateEntity state = adStateRepository.findAll().stream()
                .findFirst()
                .orElseGet(this::newAdState);
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

    private BybitManagedAdStateEntity newAdState() {
        BybitManagedAdStateEntity state = new BybitManagedAdStateEntity();
        state.setBybitAdId(bybitProperties.getP2pAdId());
        state.setPublished(false);
        state.setNextRateSourcePosition(initialRatePosition());
        return state;
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
