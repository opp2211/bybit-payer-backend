package ru.maltsev.bybitpayerbackend.ai.service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.ai.config.OpenAiProperties;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatModelCallEntity;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatSessionEntity;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionAnswer;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionBankType;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionMessageType;
import ru.maltsev.bybitpayerbackend.ai.repository.AiChatModelCallRepository;

@Component
@Slf4j
public class OpenAiChatAgentClient {

    private final OpenAiProperties properties;
    private final AiChatModelCallRepository modelCallRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final Clock clock;

    public OpenAiChatAgentClient(
            OpenAiProperties properties,
            AiChatModelCallRepository modelCallRepository,
            Clock clock
    ) {
        this.properties = properties;
        this.modelCallRepository = modelCallRepository;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build();
    }

    public boolean configured() {
        return StringUtils.hasText(properties.getApiKey());
    }

    public String model() {
        return properties.getModel();
    }

    public AiChatDecision decide(AiChatSessionEntity session, AiChatDecisionRequest decisionRequest) {
        if (!configured()) {
            throw new OpenAiUnavailableException("OpenAI API key is not configured");
        }

        Map<String, Object> requestBody = requestBody(decisionRequest);
        String promptJson = writeJson(requestBody);
        AiChatModelCallEntity call = new AiChatModelCallEntity();
        call.setSession(session);
        call.setWithdrawalRequest(session.getWithdrawalRequest());
        call.setModel(properties.getModel());
        call.setPromptJson(promptJson);
        call.setCreatedAt(clock.instant());

        try {
            HttpRequest request = HttpRequest.newBuilder(properties.getResponsesUrl())
                    .timeout(properties.getTimeout())
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(promptJson))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            call.setResponseJson(response.body());
            if (response.statusCode() >= 400) {
                throw new OpenAiUnavailableException(openAiHttpError(response.statusCode(), response.body()));
            }
            AiChatDecision decision = parseDecision(response.body());
            modelCallRepository.save(call);
            return decision;
        } catch (IOException exception) {
            call.setError(exception.getMessage());
            modelCallRepository.save(call);
            throw new OpenAiUnavailableException("OpenAI request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            call.setError(exception.getMessage());
            modelCallRepository.save(call);
            throw new OpenAiUnavailableException("OpenAI request interrupted", exception);
        } catch (RuntimeException exception) {
            call.setError(exception.getMessage());
            modelCallRepository.save(call);
            throw exception;
        }
    }

    private Map<String, Object> requestBody(AiChatDecisionRequest decisionRequest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("input", List.of(
                Map.of("role", "system", "content", decisionRequest.systemPrompt()),
                Map.of("role", "user", "content", decisionRequest.userPrompt())
        ));
        body.put("reasoning", Map.of("effort", "low"));
        body.put("text", Map.of("format", responseFormat()));
        return body;
    }

    private Map<String, Object> responseFormat() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", Map.of(
                "answer", Map.of(
                        "type", "string",
                        "enum", List.of("YES", "NO", "UNCLEAR")
                ),
                "bankType", Map.of(
                        "type", "string",
                        "enum", List.of("TBANK", "SBERBANK", "OTHER", "UNKNOWN")
                ),
                "bankName", Map.of("type", "string"),
                "messageType", Map.of(
                        "type", "string",
                        "enum", List.of(
                                "ANSWER_TO_QUESTION",
                                "BANK_ANSWER",
                                "PAYMENT_SENT",
                                "RELEASE_REQUEST",
                                "REQUISITES_CONFIRMATION",
                                "CANCEL_REQUEST",
                                "GENERAL",
                                "UNSAFE",
                                "OTHER"
                        )
                ),
                "asksHumanOperator", Map.of("type", "boolean"),
                "unsafeOrManipulative", Map.of("type", "boolean"),
                "replyText", Map.of("type", "string"),
                "summary", Map.of("type", "string")
        ));
        schema.put("required", List.of(
                "answer",
                "bankType",
                "bankName",
                "messageType",
                "asksHumanOperator",
                "unsafeOrManipulative",
                "replyText",
                "summary"
        ));
        return Map.of(
                "type", "json_schema",
                "name", "bybit_chat_agent_decision",
                "strict", true,
                "schema", schema
        );
    }

    private AiChatDecision parseDecision(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String outputText = root.path("output_text").asText(null);
            if (!StringUtils.hasText(outputText)) {
                outputText = extractOutputText(root);
            }
            if (!StringUtils.hasText(outputText)) {
                throw new OpenAiUnavailableException("OpenAI response does not contain output text");
            }
            JsonNode decision = objectMapper.readTree(outputText);
            return new AiChatDecision(
                    enumValue(AiDecisionAnswer.class, decision.path("answer").asText(), AiDecisionAnswer.UNCLEAR),
                    enumValue(AiDecisionBankType.class, decision.path("bankType").asText(), AiDecisionBankType.UNKNOWN),
                    decision.path("bankName").asText(""),
                    enumValue(AiDecisionMessageType.class, decision.path("messageType").asText(), AiDecisionMessageType.OTHER),
                    decision.path("asksHumanOperator").asBoolean(false),
                    decision.path("unsafeOrManipulative").asBoolean(false),
                    decision.path("replyText").asText(""),
                    decision.path("summary").asText("")
            );
        } catch (IOException exception) {
            throw new OpenAiUnavailableException("Failed to parse OpenAI response", exception);
        }
    }

    private String extractOutputText(JsonNode root) {
        StringBuilder result = new StringBuilder();
        for (JsonNode outputItem : root.path("output")) {
            for (JsonNode content : outputItem.path("content")) {
                if ("output_text".equals(content.path("type").asText())) {
                    result.append(content.path("text").asText());
                }
            }
        }
        return result.toString();
    }

    private String openAiHttpError(int statusCode, String responseBody) {
        String defaultMessage = "OpenAI HTTP " + statusCode;
        if (!StringUtils.hasText(responseBody)) {
            return defaultMessage;
        }
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("error");
            String message = error.path("message").asText("");
            String type = error.path("type").asText("");
            String code = error.path("code").asText("");
            StringBuilder builder = new StringBuilder(defaultMessage);
            if (StringUtils.hasText(code)) {
                builder.append(" [").append(code).append("]");
            } else if (StringUtils.hasText(type)) {
                builder.append(" [").append(type).append("]");
            }
            if (StringUtils.hasText(message)) {
                builder.append(": ").append(limit(message, 500));
            }
            return builder.toString();
        } catch (IOException exception) {
            return defaultMessage + ": " + limit(responseBody, 500);
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private <T extends Enum<T>> T enumValue(Class<T> enumType, String rawValue, T fallback) {
        if (!StringUtils.hasText(rawValue)) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, rawValue);
        } catch (IllegalArgumentException exception) {
            log.warn("Unexpected OpenAI enum value: enum={}, value={}", enumType.getSimpleName(), rawValue);
            return fallback;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize OpenAI request", exception);
        }
    }
}
