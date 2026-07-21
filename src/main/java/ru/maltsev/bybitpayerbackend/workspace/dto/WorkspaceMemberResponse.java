package ru.maltsev.bybitpayerbackend.workspace.dto;

import java.time.Instant;

public record WorkspaceMemberResponse(
        String userPublicId,
        String username,
        String email,
        String role,
        Instant createdAt
) {
}
