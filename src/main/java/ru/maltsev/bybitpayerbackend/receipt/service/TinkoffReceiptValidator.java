package ru.maltsev.bybitpayerbackend.receipt.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.receipt.dto.ParsedTinkoffReceipt;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptVerificationRequest;
import ru.maltsev.bybitpayerbackend.receipt.util.ReceiptText;

@Service
public class TinkoffReceiptValidator {

    private static final String DEFAULT_SUCCESS_STATUS = "Успешно";

    private final TinkoffReceiptPdfParser parser;

    public TinkoffReceiptValidator(TinkoffReceiptPdfParser parser) {
        this.parser = parser;
    }

    public TinkoffReceiptValidationResult validatePdf(byte[] pdf, TinkoffReceiptVerificationRequest expected) {
        return validate(parser.parse(pdf), expected);
    }

    TinkoffReceiptValidationResult validateText(String text, TinkoffReceiptVerificationRequest expected) {
        return validate(parser.parseText(text), expected);
    }

    TinkoffReceiptValidationResult validate(ParsedTinkoffReceipt parsedReceipt, TinkoffReceiptVerificationRequest expected) {
        List<String> errors = new ArrayList<>();
        String rawText = parsedReceipt.rawText();

        if (!containsHumanValue(rawText, DEFAULT_SUCCESS_STATUS)) {
            errors.add("В чеке не найден ожидаемый статус: " + DEFAULT_SUCCESS_STATUS);
        }
        if (!containsAmount(parsedReceipt, expected.amount())) {
            errors.add("В чеке не найдена ожидаемая сумма: " + expected.amount());
        }
        if (!containsHumanValue(rawText, expected.recipient())) {
            errors.add("В чеке не найден ожидаемый получатель: " + expected.recipient());
        }
        if (!containsPhone(rawText, expected.phone())) {
            errors.add("В чеке не найден ожидаемый телефон: " + expected.phone());
        }
        if (!containsHumanValue(rawText, expected.bank())) {
            errors.add("В чеке не найден ожидаемый банк: " + expected.bank());
        }

        return new TinkoffReceiptValidationResult(errors.isEmpty(), parsedReceipt.data(), List.copyOf(errors));
    }

    private boolean containsHumanValue(String source, String expected) {
        String normalizedSource = ReceiptText.normalizeHuman(source);
        String normalizedExpected = ReceiptText.normalizeHuman(expected);
        return ReceiptText.hasText(normalizedExpected) && normalizedSource.contains(normalizedExpected);
    }

    private boolean containsPhone(String source, String expectedPhone) {
        String normalizedExpected = ReceiptText.normalizePhoneDigits(expectedPhone);
        if (!ReceiptText.hasText(normalizedExpected)) {
            return false;
        }

        String normalizedSource = ReceiptText.normalizePhoneDigits(source);
        return normalizedSource.contains(normalizedExpected);
    }

    private boolean containsAmount(ParsedTinkoffReceipt parsedReceipt, BigDecimal expectedAmount) {
        if (expectedAmount == null) {
            return false;
        }
        BigDecimal parsedAmount = parsedReceipt.data().amount();
        if (parsedAmount != null && parsedAmount.compareTo(expectedAmount) == 0) {
            return true;
        }

        return ReceiptText.extractAmounts(parsedReceipt.rawText()).stream()
                .anyMatch(actual -> actual.compareTo(expectedAmount) == 0);
    }
}
