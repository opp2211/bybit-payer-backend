package ru.maltsev.bybitpayerbackend.workspace.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateWorkspaceRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank String bybitApiKey,
        @NotBlank String bybitApiSecret,
        @NotBlank String bybitP2pAdId,
        @Email String receiptEmail,
        @NotBlank String imapHost,
        @NotNull @Min(1) @Max(65535) Integer imapPort,
        @NotBlank String imapUsername,
        @NotBlank String imapPassword
) {
}
