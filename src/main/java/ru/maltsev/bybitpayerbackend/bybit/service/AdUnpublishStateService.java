package ru.maltsev.bybitpayerbackend.bybit.service;

import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.maltsev.bybitpayerbackend.bybit.entity.BybitManagedAdStateEntity;
import ru.maltsev.bybitpayerbackend.bybit.repository.BybitManagedAdStateRepository;

@Service
@RequiredArgsConstructor
public class AdUnpublishStateService {

    private final BybitManagedAdStateRepository repository;
    private final Clock clock;

    @Transactional
    public void markPending(Long stateId) {
        BybitManagedAdStateEntity state = repository.findById(stateId).orElseThrow();
        if (!state.isUnpublishPending()) {
            state.setUnpublishPending(true);
            state.setUnpublishPendingSince(Instant.now(clock));
            state.setUnpublishFailureCount(0);
            state.setUnpublishNextAttemptAt(null);
        }
    }

    @Transactional
    public void markFailed(Long stateId, RuntimeException exception) {
        BybitManagedAdStateEntity state = repository.findById(stateId).orElseThrow();
        Instant now = Instant.now(clock);
        int failures = Math.min(1000, state.getUnpublishFailureCount() + 1);
        long delaySeconds = Math.min(60, 5L << Math.min(failures, 4));
        state.setUnpublishFailureCount(failures);
        state.setUnpublishLastAttemptAt(now);
        state.setUnpublishNextAttemptAt(now.plusSeconds(delaySeconds));
        String message = exception.getMessage();
        state.setLastError(message == null ? exception.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 250)));
    }

    @Transactional
    public void markUnpublished(Long stateId) {
        BybitManagedAdStateEntity state = repository.findById(stateId).orElseThrow();
        state.setPublished(false);
        state.setUnpublishPending(false);
        state.setUnpublishPendingSince(null);
        state.setUnpublishLastAttemptAt(Instant.now(clock));
        state.setUnpublishNextAttemptAt(null);
        state.setUnpublishFailureCount(0);
        state.setLastError(null);
        state.setLastUpdatedAt(Instant.now(clock));
    }
}
