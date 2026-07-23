package ru.maltsev.bybitpayerbackend.ai.model;

import lombok.Getter;

@Getter
public enum AiChatStep {
    SENDER_FIRST_PARTY("Подтверждение 1 лица"),
    PAYER_BANK("Банк отправителя"),
    REQUIRED_RECEIPT_EMAIL("Обязательный чек на почту"),
    OPTIONAL_RECEIPT_EMAIL("Опциональный чек на почту"),
    THIRD_PARTY_TRANSFER("Согласие на 3 лицо"),
    FINAL_WARNING("Финальное предупреждение"),
    REQUISITES_SENT("Реквизиты отправлены"),
    PAYMENT_WAITING_RECEIPT("Ожидание чека"),
    WAITING_CANCEL("Ожидание отмены ордера"),
    OPERATOR_HANDOFF("Передано оператору"),
    COMPLETED("Завершено");

    private final String title;

    AiChatStep(String title) {
        this.title = title;
    }
}
