package ru.maltsev.bybitpayerbackend.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EncryptionService {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final EncryptionProperties properties;
    private final SecureRandom random = new SecureRandom();

    public EncryptionService(EncryptionProperties properties) {
        this.properties = properties;
    }

    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            return plaintext;
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer output = ByteBuffer.allocate(nonce.length + encrypted.length);
            output.put(nonce);
            output.put(encrypted);
            return Base64.getEncoder().encodeToString(output.array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to encrypt secret", exception);
        }
    }

    public String decrypt(String ciphertext) {
        if (!StringUtils.hasText(ciphertext)) {
            return ciphertext;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext);
            if (payload.length <= NONCE_BYTES) {
                throw new IllegalArgumentException("Encrypted secret payload is too short");
            }
            ByteBuffer input = ByteBuffer.wrap(payload);
            byte[] nonce = new byte[NONCE_BYTES];
            input.get(nonce);
            byte[] encrypted = new byte[input.remaining()];
            input.get(encrypted);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Failed to decrypt secret", exception);
        }
    }

    public String lookupHash(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(rawKey(), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(value.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to hash lookup value", exception);
        }
    }

    private SecretKeySpec aesKey() {
        return new SecretKeySpec(rawKey(), "AES");
    }

    private byte[] rawKey() {
        String configuredKey = properties.encryptionKey();
        if (!StringUtils.hasText(configuredKey)) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must contain a base64 encoded 32 byte key");
        }
        byte[] decoded = Base64.getDecoder().decode(configuredKey.trim());
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must decode to exactly 32 bytes");
        }
        return decoded;
    }
}
