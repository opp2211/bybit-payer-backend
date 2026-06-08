package ru.maltsev.bybitpayerbackend.receipt.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptVerificationRequest;

class TinkoffReceiptValidatorTests {

    private final TinkoffReceiptValidator validator = new TinkoffReceiptValidator(new TinkoffReceiptPdfParser());

    @Test
    void validatesSuccessfulReceiptText() {
        TinkoffReceiptVerificationRequest expected = new TinkoffReceiptVerificationRequest(
                new BigDecimal("12345.67"),
                "иван петров",
                "70000000000",
                "сбербанк"
        );

        TinkoffReceiptValidationResult result = validator.validateText("""
                Документ по операции
                Статус операции
                Успешно
                Сумма
                12 345,67 ₽
                Получатель
                Иван Петров
                Телефон получателя
                +7 (000) 000-00-00
                Банк получателя
                Сбербанк
                """, expected);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.receipt().amount()).isEqualByComparingTo("12345.67");
        assertThat(result.receipt().status()).isEqualTo("Успешно");
        assertThat(result.receipt().recipient()).isEqualTo("Иван Петров");
        assertThat(result.receipt().phone()).isEqualTo("+7 (000) 000-00-00");
        assertThat(result.receipt().bank()).isEqualTo("Сбербанк");
    }

    @Test
    void returnsErrorsForMismatchedReceiptText() {
        TinkoffReceiptVerificationRequest expected = new TinkoffReceiptVerificationRequest(
                new BigDecimal("9900"),
                "Петр Иванов",
                "79001112233",
                "Альфа-Банк"
        );

        TinkoffReceiptValidationResult result = validator.validateText("""
                Статус операции
                Отклонено
                Сумма
                9 000 ₽
                Получатель
                Иван Петров
                Телефон получателя
                +7 (000) 000-00-00
                Банк получателя
                Сбербанк
                """, expected);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(5);
    }
}
