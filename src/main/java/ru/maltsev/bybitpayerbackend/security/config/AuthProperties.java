package ru.maltsev.bybitpayerbackend.security.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        @NotBlank String username,
        @NotBlank
        @Pattern(
                regexp = "\\{bcrypt}\\$2[aby]\\$.*",
                message = "must contain a BCrypt hash prefixed with {bcrypt}"
        )
        String passwordHash,
        @NotBlank @Size(min = 32) String rememberMeKey,
        @NotNull Duration rememberMeValidity,
        boolean rememberMeSecure,
        @Min(1) int maxFailedAttempts,
        @NotNull Duration failureWindow
) {
}
