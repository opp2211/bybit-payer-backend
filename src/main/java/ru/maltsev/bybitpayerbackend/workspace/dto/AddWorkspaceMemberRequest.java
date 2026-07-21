package ru.maltsev.bybitpayerbackend.workspace.dto;

import jakarta.validation.constraints.NotBlank;

public record AddWorkspaceMemberRequest(
        @NotBlank String lookup
) {
}
