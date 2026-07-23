package ru.maltsev.bybitpayerbackend.ai.dto;

import jakarta.validation.constraints.NotNull;

public record AiChatAgentModeRequest(
        @NotNull Boolean enabled
) {
}
