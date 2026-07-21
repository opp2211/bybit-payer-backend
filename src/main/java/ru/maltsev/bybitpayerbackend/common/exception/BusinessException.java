package ru.maltsev.bybitpayerbackend.common.exception;

import java.util.List;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> details;

    private BusinessException(HttpStatus status, String message, List<String> details) {
        super(message);
        this.status = status;
        this.details = List.copyOf(details);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message, List.of());
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message, List.of());
    }

    public static BusinessException conflict(String message, List<String> details) {
        return new BusinessException(HttpStatus.CONFLICT, message, details);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(HttpStatus.FORBIDDEN, message, List.of());
    }

    public static BusinessException serviceUnavailable(String message) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, message, List.of());
    }

    public static BusinessException serviceUnavailable(String message, List<String> details) {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, message, details);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<String> getDetails() {
        return details;
    }
}
