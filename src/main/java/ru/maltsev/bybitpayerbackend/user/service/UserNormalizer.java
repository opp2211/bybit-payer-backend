package ru.maltsev.bybitpayerbackend.user.service;

import java.util.Locale;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class UserNormalizer {

    public String normalizeUsername(String username) {
        return normalizeLookup(username);
    }

    public String normalizeEmail(String email) {
        return normalizeLookup(email);
    }

    public String normalizeLookup(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
