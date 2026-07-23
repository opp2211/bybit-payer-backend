package ru.maltsev.bybitpayerbackend.ai.service;

public record AiChatDecisionRequest(
        String systemPrompt,
        String userPrompt
) {
}
