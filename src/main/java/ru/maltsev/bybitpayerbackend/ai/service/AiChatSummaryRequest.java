package ru.maltsev.bybitpayerbackend.ai.service;

import java.util.List;

public record AiChatSummaryRequest(
        String previousSummary,
        List<AiChatPromptMessage> messages
) {
}
