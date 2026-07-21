package ru.maltsev.bybitpayerbackend.receipt.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptValidationResult;
import ru.maltsev.bybitpayerbackend.receipt.dto.TinkoffReceiptVerificationRequest;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;

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
        assertThat(result.receipt().card()).isNull();
    }

    @Test
    void validatesSbpReceiptByTransferAmountWhenTotalIncludesCommission() {
        TinkoffReceiptVerificationRequest expected = new TinkoffReceiptVerificationRequest(
                new BigDecimal("2420"),
                "Дмитрий С.",
                "+7 (965) 079-32-70",
                "Т-банк"
        );

        TinkoffReceiptValidationResult result = validator.validateText("""
                20.07.2026 12:51:42
                Итого
                2 520 ₽
                Перевод
                По номеру телефона
                Статус
                Успешно
                Сумма
                2 420 ₽
                Комиссия
                100 ₽
                Отправитель
                Дарья Тодозакова
                Телефон получателя
                +7 (965) 079-32-70
                Получатель
                Дмитрий С.
                """, expected);

        assertThat(result.valid()).isTrue();
        assertThat(result.receipt().amount()).isEqualByComparingTo("2420");
    }

    @Test
    void rejectsUnsuccessfulReceiptTextWithMatchingPaymentDetails() {
        TinkoffReceiptVerificationRequest expected = new TinkoffReceiptVerificationRequest(
                new BigDecimal("12345.67"),
                "иван петров",
                "70000000000",
                "сбербанк"
        );

        TinkoffReceiptValidationResult result = validator.validateText("""
                Документ по операции
                Статус операции
                Неуспешно
                Сумма
                12 345,67 ₽
                Получатель
                Иван Петров
                Телефон получателя
                +7 (000) 000-00-00
                Банк получателя
                Сбербанк
                """, expected);

        assertThat(result.valid()).isFalse();
        assertThat(result.receipt().status()).isEqualTo("Неуспешно");
        assertThat(result.errors()).containsExactly("В чеке не найден ожидаемый статус: Успешно");
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

    @Test
    void validatesTBankReceiptWithoutRecipientBankLine() {
        TinkoffReceiptVerificationRequest expected = new TinkoffReceiptVerificationRequest(
                new BigDecimal("12345.67"),
                "Иван Петров",
                "70000000000",
                "Т-банк"
        );

        TinkoffReceiptValidationResult result = validator.validateText("""
                Статус операции
                Успешно
                Сумма
                12 345,67 ₽
                Получатель
                Иван Петров
                Телефон получателя
                +7 (000) 000-00-00
                """, expected);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.receipt().bank()).isNull();
    }

    @Test
    void rejectsTBankReceiptWithRecipientBankLine() {
        TinkoffReceiptVerificationRequest expected = new TinkoffReceiptVerificationRequest(
                new BigDecimal("12345.67"),
                "Иван Петров",
                "70000000000",
                "Т-банк"
        );

        TinkoffReceiptValidationResult result = validator.validateText("""
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

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .containsExactly("В чеке для Т-банк не должно быть строки «Банк получателя»");
    }

    @Test
    void rejectsOtherBankReceiptWithoutRecipientBankLine() {
        TinkoffReceiptVerificationRequest expected = new TinkoffReceiptVerificationRequest(
                new BigDecimal("12345.67"),
                "Иван Петров",
                "70000000000",
                "Сбербанк"
        );

        TinkoffReceiptValidationResult result = validator.validateText("""
                Статус операции
                Успешно
                Сумма
                12 345,67 ₽
                Получатель
                Иван Петров
                Телефон получателя
                +7 (000) 000-00-00
                Комментарий
                Перевод в Сбербанк
                """, expected);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly("В чеке не найден ожидаемый банк: Сбербанк");
    }

    @Test
    void rejectsExpectedValuesFoundOutsideParsedReceiptFields() {
        TinkoffReceiptVerificationRequest expected = new TinkoffReceiptVerificationRequest(
                new BigDecimal("9900"),
                "Петр Иванов",
                "+7 (900) 111-22-33",
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
                Альфа-Банк
                Комментарий
                Успешно; Петр Иванов; +7 (900) 111-22-33; комиссия 9 900 ₽
                """, expected);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).containsExactly(
                "В чеке не найден ожидаемый статус: Успешно",
                "В чеке не найдена ожидаемая сумма: 9900",
                "В чеке не найден ожидаемый получатель: Петр Иванов",
                "В чеке не найден ожидаемый телефон: +7 (900) 111-22-33"
        );
    }

    @Test
    void validatesTbankCardReceiptByLastFourDigitsAndRecipient() {
        TinkoffReceiptVerificationRequest expected = new TinkoffReceiptVerificationRequest(
                new BigDecimal("2420"),
                "Дмитрий С.",
                null,
                null,
                WithdrawalMethod.CARD_NUMBER,
                "2200000000001234",
                true
        );

        TinkoffReceiptValidationResult result = validator.validateText("""
                Статус
                Успешно
                Сумма
                2 420 ₽
                Карта получателя
                *1234
                Получатель
                Дмитрий С.
                """, expected);

        assertThat(result.valid()).isTrue();
        assertThat(result.receipt().card()).isEqualTo("*1234");
    }

    @Test
    void validatesOtherBankCardReceiptByMaskedCardNumber() {
        TinkoffReceiptVerificationRequest expected = new TinkoffReceiptVerificationRequest(
                new BigDecimal("2420"),
                null,
                null,
                null,
                WithdrawalMethod.CARD_NUMBER,
                "1234567890121234",
                false
        );

        TinkoffReceiptValidationResult result = validator.validateText("""
                Статус
                Успешно
                Сумма
                2 420 ₽
                Карта получателя
                123456******1234
                """, expected);

        assertThat(result.valid()).isTrue();
        assertThat(result.receipt().card()).isEqualTo("123456******1234");
    }
}
