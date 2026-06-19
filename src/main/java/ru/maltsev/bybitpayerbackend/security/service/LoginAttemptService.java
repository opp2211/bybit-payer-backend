package ru.maltsev.bybitpayerbackend.security.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.security.config.AuthProperties;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    private final AuthProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(HttpServletRequest request) {
        String key = clientKey(request);
        AttemptWindow window = attempts.get(key);
        if (window == null) {
            return false;
        }
        if (window.expired(clock.instant(), properties.failureWindow())) {
            attempts.remove(key, window);
            return false;
        }
        return window.failures() >= properties.maxFailedAttempts();
    }

    public long retryAfterSeconds(HttpServletRequest request) {
        AttemptWindow window = attempts.get(clientKey(request));
        if (window == null) {
            return 0;
        }
        Instant resetAt = window.startedAt().plus(properties.failureWindow());
        return Math.max(1, Duration.between(clock.instant(), resetAt).toSeconds());
    }

    public void registerFailure(HttpServletRequest request) {
        String key = clientKey(request);
        Instant now = clock.instant();
        attempts.compute(key, (ignored, current) -> {
            if (current == null || current.expired(now, properties.failureWindow())) {
                return new AttemptWindow(1, now);
            }
            return new AttemptWindow(current.failures() + 1, current.startedAt());
        });
    }

    public void clear(HttpServletRequest request) {
        attempts.remove(clientKey(request));
    }

    private String clientKey(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private record AttemptWindow(int failures, Instant startedAt) {

        boolean expired(Instant now, Duration window) {
            return !now.isBefore(startedAt.plus(window));
        }
    }
}
