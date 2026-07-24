package ru.maltsev.bybitpayerbackend.ai.service;

import java.util.List;

public record AiChatDecisionRequest(
        String systemPrompt,
        List<AiChatPromptMessage> messages
) {
    public AiChatDecisionRequest withValidationError(String error) {
        List<AiChatPromptMessage> retriedMessages = new java.util.ArrayList<>(messages);
        retriedMessages.add(new AiChatPromptMessage(
                "user",
                "<backend_validation_error>\n" + error
                        + "\nИсправь решение, повтори все факты, которые действительно следуют из переписки,"
                        + " и верни новое действие.\n</backend_validation_error>"
        ));
        return new AiChatDecisionRequest(systemPrompt, List.copyOf(retriedMessages));
    }
}
