package ru.maltsev.bybitpayerbackend.ai.dto;

import java.time.Instant;
import java.util.List;

public record AiChatAgentResponse(
        boolean exists,
        boolean enabled,
        String status,
        String statusTitle,
        String currentStep,
        String currentStepTitle,
        boolean autoReceiptEnabled,
        boolean operatorRequired,
        List<String> suggestedMessages,
        String suggestedReason,
        Instant suggestedAt,
        String lastDecisionSummary
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
                List.of(),
                null,
                null,
                null
        );
    }
}
