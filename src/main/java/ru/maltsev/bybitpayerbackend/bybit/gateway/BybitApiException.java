package ru.maltsev.bybitpayerbackend.bybit.gateway;

public class BybitApiException extends RuntimeException {

    private final boolean retryable;

    public BybitApiException(String message) {
        this(message, false);
    }

    public BybitApiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public BybitApiException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public BybitApiException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
