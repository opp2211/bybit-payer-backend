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
    private static final String BANK_WITHOUT_RECIPIENT_LABEL = "Т-банк";
    private static final List<String> RECIPIENT_BANK_LABELS = List.of(
            "Банк получателя",
            "Банк карты получателя"
    );

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
        var receipt = parsedReceipt.data();
        String rawText = parsedReceipt.rawText();

        if (!matchesHumanValue(receipt.status(), DEFAULT_SUCCESS_STATUS)) {
            errors.add("В чеке не найден ожидаемый статус: " + DEFAULT_SUCCESS_STATUS);
        }
        if (!matchesAmount(receipt.amount(), expected.amount())) {
            errors.add("В чеке не найдена ожидаемая сумма: " + expected.amount());
        }
        if (!matchesHumanValue(receipt.recipient(), expected.recipient())) {
            errors.add("В чеке не найден ожидаемый получатель: " + expected.recipient());
        }
        if (!matchesPhone(receipt.phone(), expected.phone())) {
            errors.add("В чеке не найден ожидаемый телефон: " + expected.phone());
        }
        if (isBankWithoutRecipientLabel(expected.bank()) && containsRecipientBankLine(rawText)) {
            errors.add("В чеке для Т-банк не должно быть строки «Банк получателя»");
        } else if (!isBankWithoutRecipientLabel(expected.bank())
                && !matchesHumanValue(receipt.bank(), expected.bank())) {
            errors.add("В чеке не найден ожидаемый банк: " + expected.bank());
        }

        return new TinkoffReceiptValidationResult(errors.isEmpty(), receipt, List.copyOf(errors));
    }

    private boolean matchesHumanValue(String source, String expected) {
        String normalizedSource = ReceiptText.normalizeHuman(source);
        String normalizedExpected = ReceiptText.normalizeHuman(expected);
        return ReceiptText.hasText(normalizedExpected) && normalizedSource.equals(normalizedExpected);
    }

    private boolean isBankWithoutRecipientLabel(String bank) {
        return ReceiptText.normalizeHuman(BANK_WITHOUT_RECIPIENT_LABEL)
                .equals(ReceiptText.normalizeHuman(bank));
    }

    private boolean containsRecipientBankLine(String text) {
        return ReceiptText.nullToEmpty(text).lines()
                .map(ReceiptText::normalizeHuman)
                .anyMatch(line -> RECIPIENT_BANK_LABELS.stream()
                        .map(ReceiptText::normalizeHuman)
                        .anyMatch(label -> line.equals(label) || line.startsWith(label + " ")));
    }

    boolean matchesPhone(String actualPhone, String expectedPhone) {
        String normalizedActual = ReceiptText.normalizePhoneDigits(actualPhone);
        String normalizedExpected = ReceiptText.normalizePhoneDigits(expectedPhone);
        return ReceiptText.hasText(normalizedExpected) && normalizedActual.equals(normalizedExpected);
    }

    private boolean matchesAmount(BigDecimal actualAmount, BigDecimal expectedAmount) {
        return actualAmount != null && expectedAmount != null && actualAmount.compareTo(expectedAmount) == 0;
    }
}
