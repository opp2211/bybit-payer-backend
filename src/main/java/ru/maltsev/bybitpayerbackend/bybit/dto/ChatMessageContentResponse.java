package ru.maltsev.bybitpayerbackend.bybit.dto;

public record ChatMessageContentResponse(
        ChatMessageContentType type,
        String text,
        String url,
        String fileName
) {
}
