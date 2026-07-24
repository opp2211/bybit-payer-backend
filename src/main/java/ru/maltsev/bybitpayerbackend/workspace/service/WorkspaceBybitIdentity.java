package ru.maltsev.bybitpayerbackend.workspace.service;

public record WorkspaceBybitIdentity(
        String userId,
        String accountId,
        String nickname
) {
}
