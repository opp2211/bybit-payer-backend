package ru.maltsev.bybitpayerbackend.security.dto;

public record CsrfTokenResponse(
        String headerName,
        String token
) {
}
