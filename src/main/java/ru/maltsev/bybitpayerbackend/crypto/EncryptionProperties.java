package ru.maltsev.bybitpayerbackend.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record EncryptionProperties(
        String encryptionKey
) {
}
