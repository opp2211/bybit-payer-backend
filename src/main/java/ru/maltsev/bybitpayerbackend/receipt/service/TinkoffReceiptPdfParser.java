package ru.maltsev.bybitpayerbackend.receipt.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import ru.maltsev.bybitpayerbackend.receipt.dto.ParsedTinkoffReceipt;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptData;
import ru.maltsev.bybitpayerbackend.receipt.exception.ReceiptPdfParseException;
import ru.maltsev.bybitpayerbackend.receipt.util.ReceiptText;

@Service
public class TinkoffReceiptPdfParser {

    private static final List<String> STATUS_LABELS = List.of("Статус операции", "Статус", "Состояние");
    private static final List<String> AMOUNT_LABELS = List.of("Сумма операции", "Сумма перевода", "Сумма", "Итого");
    private static final List<String> RECIPIENT_LABELS = List.of("ФИО получателя", "Получатель", "Кому");
    private static final List<String> PHONE_LABELS = List.of("Телефон получателя", "Номер телефона", "Телефон");
    private static final List<String> BANK_LABELS = List.of("Банк получателя", "Банк карты получателя");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?iu)(?:\\+7|8)\\s*\\(?\\d{3}\\)?[\\d\\s().-]{7,}");
    private static final List<String> KNOWN_STATUSES = List.of("Успешно", "Исполнено", "Выполнено", "Отклонено", "Отменено");

    public ParsedTinkoffReceipt parse(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return parseText(stripper.getText(document));
        } catch (IOException exception) {
            throw new ReceiptPdfParseException("Не удалось прочитать PDF-чек", exception);
        }
    }

    ParsedTinkoffReceipt parseText(String text) {
        String rawText = ReceiptText.nullToEmpty(text).replace('\u00A0', ' ');
        TinkoffReceiptData data = new TinkoffReceiptData(
                extractAmount(rawText).orElse(null),
                extractStatus(rawText).orElse(null),
                extractLineValue(rawText, RECIPIENT_LABELS).orElse(null),
                extractPhone(rawText).orElse(null),
                extractLineValue(rawText, BANK_LABELS).orElse(null)
        );
        return new ParsedTinkoffReceipt(data, rawText);
    }

    private Optional<BigDecimal> extractAmount(String text) {
        Optional<String> labeledAmount = extractLineValue(text, AMOUNT_LABELS);
        if (labeledAmount.isPresent()) {
            Optional<BigDecimal> amount = ReceiptText.extractFirstAmountCandidate(labeledAmount.get());
            if (amount.isPresent()) {
                return amount;
            }
        }
        return ReceiptText.extractAmounts(text).stream().findFirst();
    }

    private Optional<String> extractStatus(String text) {
        Optional<String> labeledStatus = extractLineValue(text, STATUS_LABELS);
        if (labeledStatus.isPresent()) {
            String normalizedValue = ReceiptText.normalizeHuman(labeledStatus.get());
            for (String status : KNOWN_STATUSES) {
                if (normalizedValue.contains(ReceiptText.normalizeHuman(status))) {
                    return Optional.of(status);
                }
            }
            return labeledStatus.map(this::cleanValue);
        }

        String normalizedText = ReceiptText.normalizeHuman(text);
        return KNOWN_STATUSES.stream()
                .filter(status -> normalizedText.contains(ReceiptText.normalizeHuman(status)))
                .findFirst();
    }

    private Optional<String> extractPhone(String text) {
        Optional<String> labeledPhone = extractLineValue(text, PHONE_LABELS).flatMap(this::findPhone);
        if (labeledPhone.isPresent()) {
            return labeledPhone;
        }
        return findPhone(text);
    }

    private Optional<String> findPhone(String text) {
        Matcher matcher = PHONE_PATTERN.matcher(ReceiptText.nullToEmpty(text));
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(cleanValue(matcher.group()));
    }

    private Optional<String> extractLineValue(String text, List<String> labels) {
        List<String> lines = ReceiptText.nullToEmpty(text).lines()
                .map(this::cleanValue)
                .filter(ReceiptText::hasText)
                .toList();

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String lowerLine = line.toLowerCase(Locale.ROOT).stripLeading();
            for (String label : labels) {
                String lowerLabel = label.toLowerCase(Locale.ROOT);
                if (!lowerLine.startsWith(lowerLabel)) {
                    continue;
                }

                String value = line.substring(Math.min(line.length(), label.length()))
                        .replaceFirst("^[\\s:.-]+", "");
                if (ReceiptText.hasText(value)) {
                    return Optional.of(cleanValue(value));
                }

                Optional<String> nextLineValue = findNextValueLine(lines, index + 1);
                if (nextLineValue.isPresent()) {
                    return nextLineValue;
                }
            }
        }

        return Optional.empty();
    }

    private Optional<String> findNextValueLine(List<String> lines, int startIndex) {
        for (int index = startIndex; index < lines.size(); index++) {
            String line = cleanValue(lines.get(index));
            if (!ReceiptText.hasText(line)) {
                continue;
            }
            if (looksLikeLabel(line)) {
                return Optional.empty();
            }
            return Optional.of(line);
        }
        return Optional.empty();
    }

    private boolean looksLikeLabel(String line) {
        String normalizedLine = ReceiptText.normalizeHuman(line);
        return normalizedLine.endsWith("операции")
                || normalizedLine.equals("статус")
                || normalizedLine.equals("сумма")
                || normalizedLine.equals("получатель")
                || normalizedLine.equals("телефон")
                || normalizedLine.equals("банк получателя");
    }

    private String cleanValue(String value) {
        return ReceiptText.nullToEmpty(value)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
