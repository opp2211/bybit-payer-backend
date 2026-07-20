package ru.maltsev.bybitpayerbackend.common.service;

import java.security.SecureRandom;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

@Component
public class PublicIdGenerator {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final int PUBLIC_ID_LENGTH = 7;
    private static final int MAX_ATTEMPTS = 64;

    private final SecureRandom random = new SecureRandom();

    public String generate(Predicate<String> exists) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String value = randomHex();
            if (!exists.test(value)) {
                return value;
            }
        }
        throw new IllegalStateException("Failed to generate unique public id");
    }

    private String randomHex() {
        StringBuilder builder = new StringBuilder(PUBLIC_ID_LENGTH);
        for (int index = 0; index < PUBLIC_ID_LENGTH; index++) {
            builder.append(HEX[random.nextInt(HEX.length)]);
        }
        return builder.toString();
    }
}
