package ru.maltsev.bybitpayerbackend.ai.dto;

import java.time.Instant;
import java.util.List;

import ru.maltsev.bybitpayerbackend.ai.model.AiChatAction;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatAgentMode;

public record AiChatAgentResponse(
        boolean exists,
        AiChatAgentMode mode,
        String modeTitle,
        String status,
        String statusTitle,
        String currentStep,
        String currentStepTitle,
        boolean autoReceiptEnabled,
        boolean operatorRequired,
        List<String> suggestedMessages,
        String suggestedReason,
        Instant suggestedAt,
        String lastDecisionSummary,
        AiChatAction lastAction,
        String conversationSummary,
        Instant summaryUpdatedAt,
        String operatorHandoffReason
) {
    public static AiChatAgentResponse absent() {
        return new AiChatAgentResponse(
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
