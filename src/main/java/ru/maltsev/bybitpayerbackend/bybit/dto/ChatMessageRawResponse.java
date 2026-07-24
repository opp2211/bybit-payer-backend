package ru.maltsev.bybitpayerbackend.bybit.dto;

public record ChatMessageRawResponse(
        Integer msgType,
        Integer msgCode,
        String roleType,
        String contentType,
        String accountId,
        String userId,
        String nickName
) {
}
