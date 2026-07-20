package ru.maltsev.bybitpayerbackend.workspace.service;

public record WorkspaceSecrets(
        String bybitApiKey,
        String bybitApiSecret,
        String bybitP2pAdId,
        String imapPassword
) {
}
