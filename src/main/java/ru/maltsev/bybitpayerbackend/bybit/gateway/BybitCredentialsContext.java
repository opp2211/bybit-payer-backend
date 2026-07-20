package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

@Component
public class BybitCredentialsContext {

    private final ThreadLocal<BybitCredentials> currentCredentials = new ThreadLocal<>();

    public Optional<BybitCredentials> current() {
        return Optional.ofNullable(currentCredentials.get());
    }

    public void runWith(BybitCredentials credentials, Runnable action) {
        callWith(credentials, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callWith(BybitCredentials credentials, Supplier<T> action) {
        BybitCredentials previous = currentCredentials.get();
        currentCredentials.set(credentials);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                currentCredentials.remove();
            } else {
                currentCredentials.set(previous);
            }
        }
    }
}
