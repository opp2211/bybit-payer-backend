package ru.maltsev.bybitpayerbackend.ai.dto;

import jakarta.validation.constraints.NotNull;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatAgentMode;

public record AiChatAgentModeRequest(
        @NotNull AiChatAgentMode mode
) {
}
