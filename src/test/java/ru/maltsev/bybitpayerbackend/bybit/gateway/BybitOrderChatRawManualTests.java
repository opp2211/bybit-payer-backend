package ru.maltsev.bybitpayerbackend.bybit.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

class BybitOrderChatRawManualTests {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String DEFAULT_ORDER_ID = "2074865336971419648";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void printsRawOrderChatMessages() throws Exception {
        String apiKey = config("bybit.api-key", "BYBIT_API_KEY", null);
        String apiSecret = config("bybit.api-secret", "BYBIT_API_SECRET", null);
        String baseUrl = trimTrailingSlash(config("bybit.base-url", "BYBIT_BASE_URL", "https://api.bybit.com"));
        String recvWindow = config("bybit.recv-window-ms", "BYBIT_RECV_WINDOW_MS", "10000");
        String orderId = config("bybit.chat.order-id", "BYBIT_CHAT_ORDER_ID", DEFAULT_ORDER_ID);
        int pageSize = Integer.parseInt(config("bybit.chat.page-size", "BYBIT_CHAT_PAGE_SIZE", "30"));
        int maxPages = Integer.parseInt(config("bybit.chat.max-pages", "BYBIT_CHAT_MAX_PAGES", "20"));

        assertThat(apiKey).as("Set BYBIT_API_KEY or -Dbybit.api-key").isNotBlank();
        assertThat(apiSecret).as("Set BYBIT_API_SECRET or -Dbybit.api-secret").isNotBlank();
        assertThat(baseUrl).as("Set BYBIT_BASE_URL or -Dbybit.base-url").isNotBlank();
        assertThat(orderId).as("Set BYBIT_CHAT_ORDER_ID or -Dbybit.chat.order-id").isNotBlank();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        for (int page = 1; page <= maxPages; page++) {
            String bodyJson = requestBody(orderId, page, pageSize);
            long timestamp = System.currentTimeMillis();
            String signature = hmacSha256(timestamp + apiKey + recvWindow + bodyJson, apiSecret);

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v5/p2p/order/message/listpage"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-BAPI-API-KEY", apiKey)
                    .header("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                    .header("X-BAPI-RECV-WINDOW", recvWindow)
                    .header("X-BAPI-SIGN", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            System.out.printf(
                    "%n=== Bybit raw chat response: orderId=%s, page=%d, size=%d, httpStatus=%d ===%n",
                    orderId,
                    page,
                    pageSize,
                    response.statusCode()
            );
            System.out.println(response.body());

            assertThat(response.statusCode()).isBetween(200, 299);
            assertSuccessfulBybitResponse(response.body());

            int messagesOnPage = messageCount(response.body());
            if (messagesOnPage < pageSize) {
                break;
            }
        }
    }

    private String requestBody(String orderId, int page, int pageSize) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderId", orderId);
        request.put("currentPage", String.valueOf(page));
        request.put("size", String.valueOf(pageSize));
        return objectMapper.writeValueAsString(request);
    }

    private void assertSuccessfulBybitResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode retCode = root.has("retCode") ? root.path("retCode") : root.path("ret_code");
        if (!retCode.isMissingNode()) {
            assertThat(retCode.asInt()).as(responseBody).isZero();
        }
    }

    private int messageCount(String responseBody) throws Exception {
        JsonNode messages = objectMapper.readTree(responseBody).path("result").path("result");
        return messages.isArray() ? messages.size() : 0;
    }

    private String config(String systemProperty, String environmentVariable, String defaultValue) {
        String propertyValue = System.getProperty(systemProperty);
        if (StringUtils.hasText(propertyValue)) {
            return propertyValue.trim();
        }
        String environmentValue = System.getenv(environmentVariable);
        if (StringUtils.hasText(environmentValue)) {
            return environmentValue.trim();
        }
        return defaultValue;
    }

    private String trimTrailingSlash(String value) {
        return value == null ? null : value.trim().replaceAll("/+$", "");
    }

    private String hmacSha256(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
        byte[] bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
