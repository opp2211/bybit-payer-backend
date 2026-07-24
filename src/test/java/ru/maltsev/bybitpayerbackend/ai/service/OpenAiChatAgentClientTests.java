package ru.maltsev.bybitpayerbackend.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ru.maltsev.bybitpayerbackend.ai.config.OpenAiProperties;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatModelCallEntity;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatSessionEntity;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatAction;
import ru.maltsev.bybitpayerbackend.ai.model.AiModelCallPurpose;
import ru.maltsev.bybitpayerbackend.ai.repository.AiChatModelCallRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;

class OpenAiChatAgentClientTests {

    @Test
    void sendsFullInputWithStrictSchemaAndParsesDecision() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String structuredOutput = """
                    {"action":"SEND_MESSAGES","messages":["Да, Иван В."],"finalWarning":"",
                    "firstParty":"UNKNOWN","payerBankType":"UNKNOWN","payerBankName":"",
                    "receiptEmail":"UNKNOWN","thirdPartyTransfer":"UNKNOWN",
                    "paymentClaim":"NOT_MENTIONED","handoffReason":"",
                    "summary":"Подтвердил имя получателя"}
                    """;
            byte[] response = new ObjectMapper().writeValueAsBytes(java.util.Map.of(
                    "output", List.of(java.util.Map.of(
                            "content", List.of(java.util.Map.of(
                                    "type", "output_text",
                                    "text", structuredOutput
                            ))
                    ))
            ));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiChatModelCallRepository repository = mock(AiChatModelCallRepository.class);
            when(repository.save(any(AiChatModelCallEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            OpenAiChatAgentClient client = client(server, repository);
            AiChatSessionEntity session = session();

            AiChatDecision decision = client.decide(session, new AiChatDecisionRequest(
                    "system",
                    List.of(
                            new AiChatPromptMessage("user", "контекст"),
                            new AiChatPromptMessage("assistant", "Привет"),
                            new AiChatPromptMessage("user", "перевожу Ивану?")
                    )
            ));

            assertThat(decision.action()).isEqualTo(AiChatAction.SEND_MESSAGES);
            assertThat(decision.messages()).containsExactly("Да, Иван В.");
            JsonNode body = new ObjectMapper().readTree(requestBody.get());
            assertThat(body.path("model").asText()).isEqualTo("gpt-5.6-terra");
            assertThat(body.path("store").asBoolean()).isFalse();
            assertThat(body.path("input")).hasSize(4);
            assertThat(body.path("text").path("format").path("strict").asBoolean()).isTrue();
            assertThat(body.path("text").path("format").path("schema").path("additionalProperties").asBoolean())
                    .isFalse();

            ArgumentCaptor<AiChatModelCallEntity> callCaptor = ArgumentCaptor.forClass(AiChatModelCallEntity.class);
            verify(repository).save(callCaptor.capture());
            assertThat(callCaptor.getValue().getPurpose()).isEqualTo(AiModelCallPurpose.DECISION);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void includesOpenAiErrorDetailsForHttpFailures() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            byte[] response = """
                    {
                      "error": {
                        "message": "Project is not allowed to use this model",
                        "type": "invalid_request_error",
                        "code": "model_not_allowed"
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(403, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiChatModelCallRepository modelCallRepository = mock(AiChatModelCallRepository.class);
            when(modelCallRepository.save(any(AiChatModelCallEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            OpenAiChatAgentClient client = client(server, modelCallRepository);

            assertThatThrownBy(() -> client.decide(
                    session(),
                    new AiChatDecisionRequest(
                            "system",
                            List.of(new AiChatPromptMessage("user", "user"))
                    )
            ))
                    .isInstanceOf(OpenAiUnavailableException.class)
                    .hasMessageContaining("OpenAI HTTP 403")
                    .hasMessageContaining("model_not_allowed")
                    .hasMessageContaining("Project is not allowed to use this model");
        } finally {
            server.stop(0);
        }
    }

    private OpenAiChatAgentClient client(HttpServer server, AiChatModelCallRepository repository) {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setApiKey("test-key");
        properties.setModel("gpt-5.6-terra");
        properties.setResponsesUrl(serverAddress(server));
        return new OpenAiChatAgentClient(
                properties,
                repository,
                Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), java.time.ZoneOffset.UTC)
        );
    }

    private AiChatSessionEntity session() {
        AiChatSessionEntity session = new AiChatSessionEntity();
        session.setWithdrawalRequest(new WithdrawalRequestEntity());
        return session;
    }

    private java.net.URI serverAddress(HttpServer server) {
        int port = server.getAddress().getPort();
        return java.net.URI.create("http://127.0.0.1:" + port + "/v1/responses");
    }
}
