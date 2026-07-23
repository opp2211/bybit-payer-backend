package ru.maltsev.bybitpayerbackend.ai.service;

public class OpenAiUnavailableException extends RuntimeException {

    public OpenAiUnavailableException(String message) {
        super(message);
    }

    public OpenAiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
