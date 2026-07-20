package ru.maltsev.bybitpayerbackend.workspace.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitCredentials;
import ru.maltsev.bybitpayerbackend.crypto.EncryptionService;
import ru.maltsev.bybitpayerbackend.receipt.service.ReceiptMailbox;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

@Service
@RequiredArgsConstructor
public class WorkspaceSecretService {

    private final EncryptionService encryptionService;

    public void storeSecrets(
            WorkspaceEntity workspace,
            String bybitApiKey,
            String bybitApiSecret,
            String imapPassword
    ) {
        workspace.setBybitApiKeyEncrypted(encryptionService.encrypt(bybitApiKey.trim()));
        workspace.setBybitApiKeyHash(encryptionService.lookupHash(bybitApiKey));
        workspace.setBybitApiSecretEncrypted(encryptionService.encrypt(bybitApiSecret.trim()));
        workspace.setImapPasswordEncrypted(encryptionService.encrypt(imapPassword.trim()));
    }

    public WorkspaceSecrets reveal(WorkspaceEntity workspace) {
        return new WorkspaceSecrets(
                encryptionService.decrypt(workspace.getBybitApiKeyEncrypted()),
                encryptionService.decrypt(workspace.getBybitApiSecretEncrypted()),
                workspace.getBybitP2pAdId(),
                encryptionService.decrypt(workspace.getImapPasswordEncrypted())
        );
    }

    public BybitCredentials bybitCredentials(WorkspaceEntity workspace) {
        WorkspaceSecrets secrets = reveal(workspace);
        return new BybitCredentials(
                secrets.bybitApiKey(),
                secrets.bybitApiSecret(),
                secrets.bybitP2pAdId()
        );
    }

    public ReceiptMailbox receiptMailbox(WorkspaceEntity workspace) {
        return new ReceiptMailbox(
                workspace.getImapHost(),
                workspace.getImapPort(),
                workspace.getImapUsername(),
                reveal(workspace).imapPassword()
        );
    }

    public String apiKeyHash(String bybitApiKey) {
        return encryptionService.lookupHash(bybitApiKey);
    }
}
