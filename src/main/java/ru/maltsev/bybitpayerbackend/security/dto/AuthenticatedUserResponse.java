package ru.maltsev.bybitpayerbackend.security.dto;

public record AuthenticatedUserResponse(
        String publicId,
        String username,
        String email,
        String role,
        boolean emailVerified
) {
}
