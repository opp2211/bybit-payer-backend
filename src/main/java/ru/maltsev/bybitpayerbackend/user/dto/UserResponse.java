package ru.maltsev.bybitpayerbackend.user.dto;

public record UserResponse(
        String publicId,
        String username,
        String email,
        String role,
        boolean emailVerified
) {
}
