package ru.maltsev.bybitpayerbackend.ai.model;

public enum AiDecisionMessageType {
    ANSWER_TO_QUESTION,
    BANK_ANSWER,
    PAYMENT_SENT,
    RELEASE_REQUEST,
    REQUISITES_CONFIRMATION,
    CANCEL_REQUEST,
    GENERAL,
    UNSAFE,
    OTHER
}
