package ru.maltsev.bybitpayerbackend.bybit.gateway;

public record BybitReadiness(
        boolean available,
        String mode,
        String message
) {
}
