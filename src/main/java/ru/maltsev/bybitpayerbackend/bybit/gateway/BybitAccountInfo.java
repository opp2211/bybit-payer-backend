package ru.maltsev.bybitpayerbackend.bybit.gateway;

public record BybitAccountInfo(
        String userId,
        String accountId,
        String nickname
) {
}
