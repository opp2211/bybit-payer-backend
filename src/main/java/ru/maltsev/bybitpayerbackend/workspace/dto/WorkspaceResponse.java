package ru.maltsev.bybitpayerbackend.workspace.dto;

import java.time.Instant;

public record WorkspaceResponse(
        String publicId,
        String name,
        String ownerPublicId,
        String ownerUsername,
        String currentUserRole,
        String bybitP2pAdId,
        String bybitNickname,
        String receiptEmail,
        String imapHost,
        Integer imapPort,
        String imapUsername,
        boolean enabled,
        Instant createdAt
) {
}
