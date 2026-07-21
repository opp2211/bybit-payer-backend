package ru.maltsev.bybitpayerbackend.withdrawal.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.common.exception.BusinessException;

@Component
public class WithdrawalInputNormalizer {

    public BigDecimal normalizeAmount(BigDecimal amountRub) {
        if (amountRub == null || amountRub.signum() <= 0) {
            throw BusinessException.badRequest("amountRub must be a positive integer");
        }
        BigDecimal normalized = amountRub.stripTrailingZeros();
        if (normalized.scale() > 0) {
            throw BusinessException.badRequest("amountRub must not contain fractional part");
        }
        return normalized;
    }

    public String normalizePhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() == 10) {
            return "+7" + digits;
        }
        if (digits.length() == 11 && (digits.startsWith("7") || digits.startsWith("8"))) {
            return "+7" + digits.substring(1);
        }
        throw BusinessException.badRequest("recipientPhone must be a valid Russian phone number");
    }

    public String normalizeRecipientName(String recipientName) {
        String value = recipientName == null ? "" : recipientName.trim();
        if (!StringUtils.hasText(value)) {
            throw BusinessException.badRequest("recipientName must not be blank");
        }
        return value;
    }

    public String normalizeCardNumber(String cardNumber) {
        String digits = digitsOnly(cardNumber);
        if (digits.length() != 16) {
            throw BusinessException.badRequest("recipientCardNumber must contain exactly 16 digits");
        }
        return digits;
    }

    public String normalizeAccountNumber(String accountNumber) {
        String digits = digitsOnly(accountNumber);
        if (digits.length() != 20) {
            throw BusinessException.badRequest("recipientAccountNumber must contain exactly 20 digits");
        }
        return digits;
    }

    private String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }
}
