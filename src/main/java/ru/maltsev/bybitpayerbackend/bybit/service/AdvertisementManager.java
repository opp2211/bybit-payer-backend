package ru.maltsev.bybitpayerbackend.bybit.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

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

@Service
public class AdvertisementManager {

    private static final String AD_DESCRIPTION_TEMPLATE = "Только Т-банк! ___ Заходите только на сумму %s руб.  - другие суммы - отмена! ___ Принимаю на карту 3 лица по СБП ___ Понадобится чек с офф. почты банка мне на почту";

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
    public AdvertisementSnapshot rebuildPublication() {
        lock.lock();
        try {
            Instant now = Instant.now(clock);
            List<WithdrawalRequestEntity> candidates = withdrawalRepository
                    .findByStatusInOrderByCreatedAtAscIdAsc(WithdrawalStatus.QUEUE_MANAGED_STATUSES);

            List<WithdrawalRequestEntity> published = applyQueueRules(candidates, now);
            withdrawalRepository.saveAll(candidates);

            AdvertisementSnapshot snapshot = buildSnapshot(published);
            ensureBalance(snapshot);
            persistAndPushAdState(snapshot, now);
            return snapshot;
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

    private AdvertisementSnapshot buildSnapshot(List<WithdrawalRequestEntity> published) {
        BigDecimal rate = bybitGateway.fetchReferenceRate();
        BigDecimal availableUsdt = bybitGateway.fetchAvailableUsdtBalance();
        if (published.isEmpty()) {
            return new AdvertisementSnapshot(
                    false,
                    rate,
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

        return new AdvertisementSnapshot(true, rate, minRub, maxRub, quantityUsdt, description, availableUsdt);
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

    private void persistAndPushAdState(AdvertisementSnapshot snapshot, Instant now) {
        BybitManagedAdStateEntity state = adStateRepository.findAll().stream()
                .findFirst()
                .orElseGet(this::newAdState);
        state.setBybitAdId(bybitProperties.getP2pAdId());
        state.setPublished(snapshot.published());
        state.setLastRate(snapshot.rate());
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
        } else if (StringUtils.hasText(state.getBybitAdId())) {
            bybitGateway.unpublishManagedAd(state.getBybitAdId());
        }

        adStateRepository.save(state);
    }

    private BybitManagedAdStateEntity newAdState() {
        BybitManagedAdStateEntity state = new BybitManagedAdStateEntity();
        state.setBybitAdId(bybitProperties.getP2pAdId());
        state.setPublished(false);
        return state;
    }

    private String amountKey(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private String formatRubAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
