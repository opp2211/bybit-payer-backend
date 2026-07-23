package ru.maltsev.bybitpayerbackend.bybit.dto;

import java.time.Instant;

public record FakeBybitChatMessageResponse(
        String id,
        String message,
        String direction,
        String authorName,
        String contentType,
        Instant createdAt
) {
}
