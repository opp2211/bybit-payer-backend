package ru.maltsev.bybitpayerbackend.bank.admin;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminBankRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Size(max = 128) String title,
        boolean enabled,
        @NotNull Integer sortOrder,
        List<@NotBlank @Size(max = 128) String> aliases
) {
}
