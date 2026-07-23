package ru.maltsev.bybitpayerbackend.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.ai.config.OpenAiProperties;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatModelCallEntity;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatSessionEntity;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionAnswer;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionBankType;
import ru.maltsev.bybitpayerbackend.ai.repository.AiChatModelCallRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;

class OpenAiChatAgentManualTests {

    @Test
    void callsOpenAiResponsesApi() {
        OpenAiProperties properties = properties();
        assertThat(properties.getApiKey())
                .as("Set OPENAI_API_KEY in bybit-payer-backend/.env, environment, or -Dopenai.api-key")
                .isNotBlank();

        AiChatModelCallRepository modelCallRepository = mock(AiChatModelCallRepository.class);
        when(modelCallRepository.save(any(AiChatModelCallEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OpenAiChatAgentClient client = new OpenAiChatAgentClient(
                properties,
                modelCallRepository,
                Clock.systemUTC()
        );

        AiChatSessionEntity session = new AiChatSessionEntity();
        session.setWithdrawalRequest(new WithdrawalRequestEntity());

        try {
            AiChatDecision decision = client.decide(
                    session,
                    new AiChatDecisionRequest(
                            """
                                    Ты классификатор коротких сообщений в P2P-чате.
                                    Верни структурированный ответ по JSON schema.
                                    """,
                            """
                                    Текущий вопрос продавца: "Вы будете отправлять оплату с Т-Банка?"
                                    Сообщение контрагента: "Да, буду платить с Т-Банка"
                                    """
                    )
            );

            System.out.printf(
                    "OpenAI ping ok: model=%s, answer=%s, bankType=%s, messageType=%s, summary=%s%n",
                    client.model(),
                    decision.answer(),
                    decision.bankType(),
                    decision.messageType(),
                    decision.summary()
            );

            assertThat(decision.answer()).isEqualTo(AiDecisionAnswer.YES);
            assertThat(decision.bankType()).isEqualTo(AiDecisionBankType.TBANK);
        } catch (OpenAiUnavailableException exception) {
            fail("OpenAI request failed: " + exception.getMessage(), exception);
        }
    }

    private OpenAiProperties properties() {
        Map<String, String> dotEnv = dotEnv();
        OpenAiProperties properties = new OpenAiProperties();
        properties.setApiKey(config(dotEnv, "openai.api-key", "OPENAI_API_KEY", ""));
        properties.setModel(config(dotEnv, "openai.model", "OPENAI_MODEL", "gpt-5-nano"));
        properties.setTimeout(Duration.ofSeconds(Long.parseLong(config(
                dotEnv,
                "openai.timeout-seconds",
                "OPENAI_TIMEOUT_SECONDS",
                "15"
        ))));
        return properties;
    }

    private String config(
            Map<String, String> dotEnv,
            String systemProperty,
            String environmentVariable,
            String defaultValue
    ) {
        String propertyValue = System.getProperty(systemProperty);
        if (hasText(propertyValue)) {
            return propertyValue.trim();
        }
        String environmentValue = System.getenv(environmentVariable);
        if (hasText(environmentValue)) {
            return environmentValue.trim();
        }
        return dotEnv.getOrDefault(environmentVariable, defaultValue);
    }

    private Map<String, String> dotEnv() {
        Path path = Path.of(".env");
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            Map<String, String> values = new HashMap<>();
            for (String rawLine : Files.readAllLines(path)) {
                String line = stripBom(rawLine).trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = unquote(line.substring(separator + 1).trim());
                values.put(key, value);
            }
            return values;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read backend .env", exception);
        }
    }

    private String stripBom(String value) {
        return value != null && value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
