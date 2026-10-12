package ru.maltsev.bybitpayerbackend.bybit.gateway;

import lombok.Getter;

@Getter
public class BybitApiException extends RuntimeException {

    private final boolean retryable;
    private final Integer apiCode;

    public BybitApiException(String message) {
        this(message, false);
    }

    public BybitApiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
        this.apiCode = null;
    }

    public BybitApiException(String message, int apiCode) {
        super(message);
        this.retryable = false;
        this.apiCode = apiCode;
    }

    public BybitApiException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public BybitApiException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
        this.apiCode = null;
    }
}
