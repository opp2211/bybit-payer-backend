package ru.maltsev.bybitpayerbackend.bybit.gateway;

public record BybitCredentials(
        String apiKey,
        String apiSecret,
        String p2pAdId
) {
}
