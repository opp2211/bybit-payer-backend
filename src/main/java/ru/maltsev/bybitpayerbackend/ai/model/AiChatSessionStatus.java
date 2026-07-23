package ru.maltsev.bybitpayerbackend.ai.model;

import lombok.Getter;

@Getter
public enum AiChatSessionStatus {
    WAITING_COUNTERPARTY("Ждём ответ контрагента"),
    REQUISITES_SENT("Реквизиты отправлены"),
    WAITING_CANCEL("Просим контрагента отменить ордер"),
    OPERATOR_REQUIRED("Нужен оператор"),
    COMPLETED("Завершено");

    private final String title;

    AiChatSessionStatus(String title) {
        this.title = title;
    }
}
