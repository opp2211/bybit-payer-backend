package ru.maltsev.bybitpayerbackend.bybit.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;

class HttpBybitGatewayTests {

    private static final String AD_INFO_PATH = "/v5/p2p/item/info";
    private static final String AD_UPDATE_PATH = "/v5/p2p/item/update";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void reportsMissingConfigurationWhenBaseUrlIsBlank() {
        BybitProperties properties = properties();
        properties.setApiKey("test-api-key");
        properties.setApiSecret("test-api-secret");
        properties.setBaseUrl(" ");
        properties.setP2pAdId("ad-123");
        HttpBybitGateway gateway = new HttpBybitGateway(properties, Clock.systemUTC());

        BybitReadiness readiness = gateway.checkReadiness();

        assertThat(readiness.available()).isFalse();
        assertThat(readiness.mode()).isEqualTo("CONFIG_MISSING");
        assertThat(readiness.message()).contains("base URL");
        assertThatThrownBy(gateway::fetchReferenceRate)
                .isInstanceOf(BybitApiException.class)
                .hasMessageContaining("base URL");
    }

    @Test
    void updatesOnlineAdWithPaymentTermIdsAndModifyAction() throws Exception {
        JsonNode payload = captureAdUpdatePayload(10);

        assertThat(payload.path("paymentIds")).containsExactly(objectMapper.getNodeFactory().textNode("payment-account-1"));
        assertThat(payload.path("actionType").asText()).isEqualTo("MODIFY");
        assertThat(payload.path("id").asText()).isEqualTo("ad-123");
        assertThat(payload.path("premium").asText()).isEmpty();
        assertThat(payload.path("tradingPreferenceSet").path("hasUnPostAd").isTextual()).isTrue();
        assertThat(payload.path("tradingPreferenceSet").path("hasUnPostAd").asText()).isEqualTo("1");
        assertThat(payload.path("tradingPreferenceSet").path("completeRateDay30").asText()).isEqualTo("95");
        assertThat(payload.path("tradingPreferenceSet").has("unsupportedResponseField")).isFalse();
    }

    @Test
    void relistsOfflineAdWithActiveAction() throws Exception {
        JsonNode payload = captureAdUpdatePayload(20);

        assertThat(payload.path("actionType").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void returnsBalanceFetchedDuringReadinessCheck() throws Exception {
        AtomicInteger timeRequests = new AtomicInteger();
        AtomicInteger balanceRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v5/market/time", exchange -> {
            timeRequests.incrementAndGet();
            respond(exchange, """
                    {"retCode":0,"retMsg":"OK","time":1741769463827,"result":{}}
                    """);
        });
        server.createContext("/v5/asset/transfer/query-account-coins-balance", exchange -> {
            balanceRequests.incrementAndGet();
            respond(exchange, """
                    {
                      "retCode": 0,
                      "retMsg": "OK",
                      "result": {
                        "balance": [
                          {"coin": "USDT", "transferBalance": "123.45"}
                        ]
                      }
                    }
                    """);
        });
        server.createContext(AD_INFO_PATH, exchange -> respond(exchange, """
                {"retCode":0,"retMsg":"OK","result":{"id":"ad-123"}}
                """));
        server.start();

        try {
            BybitProperties properties = properties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            properties.setApiKey("test-api-key");
            properties.setApiSecret("test-api-secret");
            properties.setP2pAdId("ad-123");
            HttpBybitGateway gateway = new HttpBybitGateway(properties, Clock.systemUTC());

            BybitReadiness readiness = gateway.checkReadiness();

            assertThat(readiness.available()).isTrue();
            assertThat(readiness.availableUsdtBalance()).isEqualByComparingTo("123.45");
            assertThat(timeRequests).hasValue(0);
            assertThat(balanceRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsAllPagesOfActiveOrders() throws Exception {
        AtomicInteger orderRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v5/p2p/order/pending/simplifyList", exchange -> {
            orderRequests.incrementAndGet();
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            int page = request.path("page").asInt();
            if (page == 1) {
                respond(exchange, """
                        {
                          "retCode": 0,
                          "retMsg": "OK",
                          "result": {
                            "count": 3,
                            "items": [
                              {"id":"order-1","amount":"1000","status":20},
                              {"id":"order-2","amount":"2000","status":30}
                            ]
                          }
                        }
                        """);
            } else {
                respond(exchange, """
                        {
                          "retCode": 0,
                          "retMsg": "OK",
                          "result": {
                            "count": 3,
                            "items": [
                              {"id":"order-3","amount":"3000","status":110}
                            ]
                          }
                        }
                        """);
            }
        });
        server.start();

        try {
            BybitProperties properties = properties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-api-key");
            properties.setApiSecret("test-api-secret");
            properties.setP2pAdId("ad-123");
            properties.setOrderPageSize(2);
            HttpBybitGateway gateway = new HttpBybitGateway(properties, Clock.systemUTC());

            List<BybitP2pOrder> orders = gateway.fetchActiveOrders();

            assertThat(orderRequests).hasValue(2);
            assertThat(orders).extracting(BybitP2pOrder::bybitOrderId)
                    .containsExactly("order-1", "order-2", "order-3");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsTerminalOrderStatusAndUsdtAmounts() throws Exception {
        Instant fixedInstant = Instant.ofEpochMilli(1741769463827L);
        AtomicReference<String> timestampHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v5/p2p/order/info", exchange -> {
            timestampHeader.set(exchange.getRequestHeaders().getFirst("X-BAPI-TIMESTAMP"));
            respond(exchange, """
                {
                  "retCode": 0,
                  "retMsg": "OK",
                  "result": {
                    "id": "order-123",
                    "amount": "10000",
                    "quantity": "108.25",
                    "fee": "0.30",
                    "status": 50
                  }
                }
                """);
        });
        server.start();

        try {
            BybitProperties properties = properties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-api-key");
            properties.setApiSecret("test-api-secret");
            properties.setP2pAdId("ad-123");
            HttpBybitGateway gateway = new HttpBybitGateway(properties, Clock.fixed(fixedInstant, java.time.ZoneOffset.UTC));

            BybitP2pOrder order = gateway.fetchOrder("order-123").orElseThrow();

            assertThat(timestampHeader).hasValue(String.valueOf(fixedInstant.toEpochMilli()));
            assertThat(order.finished()).isTrue();
            assertThat(order.quantityUsdt()).isEqualByComparingTo("108.25");
            assertThat(order.feeUsdt()).isEqualByComparingTo("0.30");
            assertThat(order.totalUsdt()).isEqualByComparingTo("108.55");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsOrderChatMessagesViaNewApiAndReusesSessionForSend() throws Exception {
        AtomicInteger orderRequests = new AtomicInteger();
        AtomicInteger sessionRequests = new AtomicInteger();
        AtomicInteger chatRequests = new AtomicInteger();
        AtomicReference<JsonNode> sendPayload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v5/p2p/order/info", exchange -> {
            orderRequests.incrementAndGet();
            respond(exchange, """
                    {
                      "retCode": 0,
                      "retMsg": "OK",
                      "result": {
                        "id": "order-123",
                        "targetUserMaskId": "target-user-mask"
                      }
                    }
                    """);
        });
        server.createContext("/v5/p2p/chat/session/getSessionId", exchange -> {
            sessionRequests.incrementAndGet();
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            assertThat(request.path("userMaskId").asText()).isEqualTo("target-user-mask");
            respond(exchange, """
                    {
                      "ret_code": 0,
                      "ret_msg": "",
                      "result": {"sessionId":"encrypted-session-id"}
                    }
                    """);
        });
        server.createContext("/v5/p2p/chat/message/listpage_v1", exchange -> {
            chatRequests.incrementAndGet();
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            assertThat(request.path("sessionId").asText()).isEqualTo("encrypted-session-id");
            assertThat(request.path("limit").asInt()).isEqualTo(2);
            if (request.path("lastId").asLong() == 0L) {
                respond(exchange, """
                {
                  "ret_code": 0,
                  "ret_msg": "SUCCESS",
                  "result": {
                    "messages": [
                      {
                        "id": "3000835349",
                        "message": "{\\\"content\\\":\\\"System\\\",\\\"msgType\\\":\\\"0\\\",\\\"msgCode\\\":\\\"1011\\\",\\\"fileName\\\":\\\"\\\"}",
                        "createDate": "1741763625000",
                        "contentType": "str",
                        "sendUserNickName": "Bybit"
                      },
                      {
                        "id": "3000835348",
                        "message": "{\\\"content\\\":\\\"Здравствуйте\\\",\\\"msgType\\\":\\\"1\\\",\\\"msgCode\\\":\\\"0\\\",\\\"fileName\\\":\\\"\\\"}",
                        "createDate": "1741763626000",
                        "contentType": "str",
                        "sendUserNickName": "Покупатель"
                      }
                    ]
                  }
                }
                """);
            } else {
                respond(exchange, """
                {
                  "ret_code": 0,
                  "ret_msg": "SUCCESS",
                  "result": {
                    "messages": [
                      {
                        "id": "3000835347",
                        "message": "{\\\"content\\\":\\\"/fiat/p2p/oss/showObj/file.jpg\\\",\\\"msgType\\\":\\\"2\\\",\\\"msgCode\\\":\\\"0\\\",\\\"fileName\\\":\\\"file.jpg\\\"}",
                        "createDate": "1741763627000",
                        "contentType": "pic",
                        "sendUserNickName": "Покупатель"
                      }
                    ]
                  }
                }
                """);
            }
        });
        server.createContext("/v5/p2p/chat/message/send_v1", exchange -> {
            sendPayload.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, """
                    {"ret_code":0,"ret_msg":"","result":{}}
                    """);
        });
        server.start();

        try {
            BybitProperties properties = properties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-api-key");
            properties.setApiSecret("test-api-secret");
            properties.setP2pAdId("ad-123");
            properties.setChatMessagePageSize(2);
            HttpBybitGateway gateway = new HttpBybitGateway(properties, Clock.systemUTC());

            List<BybitChatMessage> messages = gateway.fetchChatMessages("order-123");
            gateway.sendChatMessage("order-123", "unused-message-uuid", "Добрый день");

            assertThat(orderRequests).hasValue(1);
            assertThat(sessionRequests).hasValue(1);
            assertThat(chatRequests).hasValue(2);
            assertThat(messages).hasSize(3);
            assertThat(messages.getFirst().message()).isEqualTo("System");
            assertThat(messages.getFirst().messageCode()).isEqualTo(1011);
            assertThat(messages.get(1).message()).isEqualTo("Здравствуйте");
            assertThat(messages.get(1).nickname()).isEqualTo("Покупатель");
            assertThat(messages.getFirst().createdAt()).isEqualTo(Instant.ofEpochMilli(1741763625000L));
            assertThat(messages.get(2).fileName()).isEqualTo("file.jpg");
            assertThat(sendPayload.get().path("sessionId").asText()).isEqualTo("encrypted-session-id");
            assertThat(sendPayload.get().path("orderId").asText()).isEqualTo("order-123");
            assertThat(sendPayload.get().path("message").asText()).isEqualTo("Добрый день");
            assertThat(sendPayload.get().has("msgUuid")).isFalse();
        } finally {
            server.stop(0);
        }
    }

    private JsonNode captureAdUpdatePayload(int adStatus) throws Exception {
        AtomicReference<String> updateRequestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(AD_INFO_PATH, exchange -> respond(exchange, """
                {
                  "retCode": 0,
                  "retMsg": "OK",
                  "result": {
                    "id": "ad-123",
                    "status": %d,
                    "priceType": 0,
                    "premium": "0",
                    "paymentPeriod": 15,
                    "payments": ["377"],
                    "paymentTerms": [
                      {"id": "payment-account-1", "paymentType": 377}
                    ],
                    "tradingPreferenceSet": {
                      "hasUnPostAd": 1,
                      "isKyc": 1,
                      "isEmail": 1,
                      "isMobile": 0,
                      "hasRegisterTime": 1,
                      "registerTimeThreshold": 15,
                      "orderFinishNumberDay30": 60,
                      "completeRateDay30": "95",
                      "nationalLimit": "",
                      "hasOrderFinishNumberDay30": 1,
                      "hasCompleteRateDay30": 1,
                      "hasNationalLimit": 0,
                      "unsupportedResponseField": 123
                    }
                  }
                }
                """.formatted(adStatus)));
        server.createContext(AD_UPDATE_PATH, exchange -> {
            updateRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"retCode":0,"retMsg":"OK","result":{}}
                    """);
        });
        server.start();

        try {
            BybitProperties properties = properties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-api-key");
            properties.setApiSecret("test-api-secret");
            properties.setP2pAdId("ad-123");

            HttpBybitGateway gateway = new HttpBybitGateway(properties, Clock.systemUTC());
            gateway.updateManagedAd(new AdUpdateCommand(
                    "ad-123",
                    true,
                    new BigDecimal("92.31"),
                    new BigDecimal("1000"),
                    new BigDecimal("10000"),
                    new BigDecimal("108.3307"),
                    "Test ad"
            ));

            return objectMapper.readTree(updateRequestBody.get());
        } finally {
            server.stop(0);
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String responseBody) throws java.io.IOException {
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private BybitProperties properties() {
        BybitProperties properties = new BybitProperties();
        properties.setRetryMaxAttempts(1);
        properties.setRateLimitRequestsPerSecond(1000);
        return properties;
    }
}
