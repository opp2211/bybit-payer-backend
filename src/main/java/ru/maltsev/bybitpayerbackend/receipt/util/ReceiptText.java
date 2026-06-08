package ru.maltsev.bybitpayerbackend.receipt.util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

public final class ReceiptText {

    private static final Pattern AMOUNT_WITH_CURRENCY = Pattern.compile(
            "(?iu)(\\d{1,3}(?:[\\s\\u00A0]\\d{3})*(?:[,.]\\d{1,2})?|\\d+(?:[,.]\\d{1,2})?)\\s*(?:₽|руб\\.?|rub|р\\b)"
    );
    private static final Pattern AMOUNT_NUMBER = Pattern.compile(
            "(?iu)(\\d{1,3}(?:[\\s\\u00A0]\\d{3})*(?:[,.]\\d{1,2})?|\\d+(?:[,.]\\d{1,2})?)"
    );

    private ReceiptText() {
    }

    public static List<BigDecimal> extractAmounts(String text) {
        List<BigDecimal> amounts = new ArrayList<>();
        Matcher matcher = AMOUNT_WITH_CURRENCY.matcher(nullToEmpty(text));
        while (matcher.find()) {
            parseAmount(matcher.group(1)).ifPresent(amounts::add);
        }
        return amounts;
    }

    public static Optional<BigDecimal> extractFirstAmountCandidate(String text) {
        Matcher matcher = AMOUNT_NUMBER.matcher(nullToEmpty(text));
        if (!matcher.find()) {
            return Optional.empty();
        }
        return parseAmount(matcher.group(1));
    }

    public static String normalizeHuman(String value) {
        return nullToEmpty(value)
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String normalizePhoneDigits(String value) {
        String digits = nullToEmpty(value).replaceAll("\\D", "");
        if (digits.length() == 11 && (digits.startsWith("7") || digits.startsWith("8"))) {
            return digits.substring(1);
        }
        return digits;
    }

    public static boolean hasText(String value) {
        return StringUtils.hasText(value);
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static Optional<BigDecimal> parseAmount(String amount) {
        String normalized = nullToEmpty(amount)
                .replace("\u00A0", "")
                .replaceAll("\\s+", "")
                .replace(',', '.');
        if (!StringUtils.hasText(normalized)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(normalized));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
