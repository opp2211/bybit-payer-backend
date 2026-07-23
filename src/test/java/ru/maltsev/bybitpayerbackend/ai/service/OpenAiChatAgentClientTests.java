package ru.maltsev.bybitpayerbackend.ai.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.ai.config.OpenAiProperties;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatModelCallEntity;
import ru.maltsev.bybitpayerbackend.ai.entity.AiChatSessionEntity;
import ru.maltsev.bybitpayerbackend.ai.repository.AiChatModelCallRepository;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;

class OpenAiChatAgentClientTests {

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
                    """.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(403, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiProperties properties = new OpenAiProperties();
            properties.setApiKey("test-key");
            properties.setResponsesUrl(serverAddress(server));

            AiChatModelCallRepository modelCallRepository = mock(AiChatModelCallRepository.class);
            when(modelCallRepository.save(any(AiChatModelCallEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            OpenAiChatAgentClient client = new OpenAiChatAgentClient(
                    properties,
                    modelCallRepository,
                    Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), java.time.ZoneOffset.UTC)
            );
            AiChatSessionEntity session = new AiChatSessionEntity();
            session.setWithdrawalRequest(new WithdrawalRequestEntity());

            assertThatThrownBy(() -> client.decide(
                    session,
                    new AiChatDecisionRequest("system", "user")
            ))
                    .isInstanceOf(OpenAiUnavailableException.class)
                    .hasMessageContaining("OpenAI HTTP 403")
                    .hasMessageContaining("model_not_allowed")
                    .hasMessageContaining("Project is not allowed to use this model");
        } finally {
            server.stop(0);
        }
    }

    private java.net.URI serverAddress(HttpServer server) {
        int port = server.getAddress().getPort();
        return java.net.URI.create("http://127.0.0.1:" + port + "/v1/responses");
    }
}
