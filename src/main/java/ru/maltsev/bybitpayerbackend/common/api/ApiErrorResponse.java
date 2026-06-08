package ru.maltsev.bybitpayerbackend.common.api;

import java.util.List;

public record ApiErrorResponse(
        String message,
        List<String> details
) {

    public ApiErrorResponse(String message) {
        this(message, List.of());
    }
}
