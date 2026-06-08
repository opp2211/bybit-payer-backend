package ru.maltsev.bybitpayerbackend.withdrawal.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.config.BusinessProperties;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalEventType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.repository.WithdrawalRequestRepository;

@Service
public class AttentionWatcher {

    private final WithdrawalRequestRepository withdrawalRepository;
    private final WithdrawalEventService eventService;
    private final BusinessProperties businessProperties;
    private final Clock clock;

    public AttentionWatcher(
            WithdrawalRequestRepository withdrawalRepository,
            WithdrawalEventService eventService,
            BusinessProperties businessProperties,
            Clock clock
    ) {
        this.withdrawalRepository = withdrawalRepository;
        this.eventService = eventService;
        this.businessProperties = businessProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "30s")
    @Transactional
    public void markTimedOutWithdrawals() {
        Instant threshold = Instant.now(clock).minus(businessProperties.getAttentionTimeout());
        markPaymentInProgress(threshold);
        markPaymentVerification(threshold);
    }

    private void markPaymentInProgress(Instant threshold) {
        List<WithdrawalRequestEntity> timedOut = withdrawalRepository
                .findByStatusAndOrderFoundAtBefore(WithdrawalStatus.PAYMENT_IN_PROGRESS, threshold);
        for (WithdrawalRequestEntity withdrawal : timedOut) {
            markAttention(withdrawal, "Payment is in progress longer than attention timeout");
        }
    }

    private void markPaymentVerification(Instant threshold) {
        List<WithdrawalRequestEntity> timedOut = withdrawalRepository
                .findByStatusAndVerificationStartedAtBefore(WithdrawalStatus.PAYMENT_VERIFICATION, threshold);
        for (WithdrawalRequestEntity withdrawal : timedOut) {
            markAttention(withdrawal, "Payment verification is running longer than attention timeout");
        }
    }

    private void markAttention(WithdrawalRequestEntity withdrawal, String warning) {
        if (withdrawal.isAttentionRequired()) {
            return;
        }
        withdrawal.setAttentionRequired(true);
        withdrawal.setLastWarning(warning);
        withdrawalRepository.save(withdrawal);
        eventService.add(withdrawal, WithdrawalEventType.ATTENTION_REQUIRED, warning);
    }
}
