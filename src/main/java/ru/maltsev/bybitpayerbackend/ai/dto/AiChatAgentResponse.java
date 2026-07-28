package ru.maltsev.bybitpayerbackend.ai.dto;

import java.time.Instant;

import ru.maltsev.bybitpayerbackend.ai.model.AiChatAction;

public record AiChatAgentResponse(
        boolean exists,
        boolean enabled,
        String status,
        String statusTitle,
        String currentStep,
        String currentStepTitle,
        boolean autoReceiptEnabled,
        boolean operatorRequired,
        String lastDecisionSummary,
        AiChatAction lastAction,
        String conversationSummary,
        Instant summaryUpdatedAt,
        String operatorHandoffReason
) {
    public static AiChatAgentResponse absent() {
        return new AiChatAgentResponse(
                false,
                false,
                null,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null
        );
    }
}
