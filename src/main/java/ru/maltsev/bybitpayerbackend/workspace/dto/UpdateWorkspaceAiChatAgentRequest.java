package ru.maltsev.bybitpayerbackend.workspace.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateWorkspaceAiChatAgentRequest(
        @NotNull Boolean enabled
) {
}
