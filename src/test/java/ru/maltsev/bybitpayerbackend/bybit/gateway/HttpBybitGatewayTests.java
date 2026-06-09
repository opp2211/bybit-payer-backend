package ru.maltsev.bybitpayerbackend.bybit.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;

class HttpBybitGatewayTests {

    private static final String SELLER_CANCEL_PATH = "/fiat/otc/order/seller/proposal/cancelOrder";
    private static final String AD_INFO_PATH = "/v5/p2p/item/info";
    private static final String AD_UPDATE_PATH = "/v5/p2p/item/update";

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void submitsSellerCancelThroughWebSessionEndpoint() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> cookieHeader = new AtomicReference<>();
        AtomicReference<String> guidHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(SELLER_CANCEL_PATH, exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            cookieHeader.set(exchange.getRequestHeaders().getFirst("Cookie"));
            guidHeader.set(exchange.getRequestHeaders().getFirst("guid"));
            byte[] response = """
                    {"ret_code":0,"ret_msg":"SUCCESS","result":{}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            BybitProperties properties = properties();
            properties.setWebBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setWebSessionCookie("session=test-cookie");
            properties.setWebGuid("test-guid");
            properties.setCancelReasonCode("sellerOrderCancelReason_sellerOther");
            properties.setCancelRemark("Receipt details do not match");

            HttpBybitGateway gateway = new HttpBybitGateway(properties, Clock.systemUTC());
            gateway.requestCancel("123456789");

            JsonNode payload = objectMapper.readTree(requestBody.get());
            assertThat(payload.path("orderId").asText()).isEqualTo("123456789");
            assertThat(payload.path("cancelReasonCode").asText())
                    .isEqualTo("sellerOrderCancelReason_sellerOther");
            assertThat(payload.path("subCancelReasonCode").isNull()).isTrue();
            assertThat(payload.path("cancelRemark").asText())
                    .isEqualTo("Receipt details do not match");
            assertThat(cookieHeader.get()).isEqualTo("session=test-cookie");
            assertThat(guidHeader.get()).isEqualTo("test-guid");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsSellerCancelWithoutWebSessionCookie() {
        HttpBybitGateway gateway = new HttpBybitGateway(properties(), Clock.systemUTC());

        assertThatThrownBy(() -> gateway.requestCancel("123456789"))
                .isInstanceOf(BybitApiException.class)
                .hasMessageContaining("BYBIT_WEB_SESSION_COOKIE");
    }

    @Test
    void returnsBalanceFetchedDuringReadinessCheck() throws Exception {
        AtomicInteger balanceRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v5/market/time", exchange -> respond(exchange, """
                {"retCode":0,"retMsg":"OK","time":1741769463827,"result":{}}
                """));
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
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-api-key");
            properties.setApiSecret("test-api-secret");
            properties.setP2pAdId("ad-123");
            HttpBybitGateway gateway = new HttpBybitGateway(properties, Clock.systemUTC());

            BybitReadiness readiness = gateway.checkReadiness();

            assertThat(readiness.available()).isTrue();
            assertThat(readiness.availableUsdtBalance()).isEqualByComparingTo("123.45");
            assertThat(balanceRequests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsTerminalOrderStatusAndUsdtAmounts() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v5/market/time", exchange -> respond(exchange, """
                {"retCode":0,"retMsg":"OK","time":1741769463827,"result":{}}
                """));
        server.createContext("/v5/p2p/order/info", exchange -> respond(exchange, """
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
                """));
        server.start();

        try {
            BybitProperties properties = properties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-api-key");
            properties.setApiSecret("test-api-secret");
            properties.setP2pAdId("ad-123");
            HttpBybitGateway gateway = new HttpBybitGateway(properties, Clock.systemUTC());

            BybitP2pOrder order = gateway.fetchOrder("order-123").orElseThrow();

            assertThat(order.finished()).isTrue();
            assertThat(order.quantityUsdt()).isEqualByComparingTo("108.25");
            assertThat(order.feeUsdt()).isEqualByComparingTo("0.30");
            assertThat(order.totalUsdt()).isEqualByComparingTo("108.55");
        } finally {
            server.stop(0);
        }
    }

    private JsonNode captureAdUpdatePayload(int adStatus) throws Exception {
        AtomicReference<String> updateRequestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v5/market/time", exchange -> respond(exchange, """
                {"retCode":0,"retMsg":"OK","time":1741769463827,"result":{}}
                """));
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
