package ru.maltsev.bybitpayerbackend.bybit.gateway;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "bybit", name = "enabled", havingValue = "true")
public class HttpBybitGateway implements BybitGateway {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SELLER_CANCEL_ENDPOINT = "/fiat/otc/order/seller/proposal/cancelOrder";
    private static final int AD_STATUS_ONLINE = 10;
    private static final int ORDER_STATUS_WAITING_BUYER_PAY = 10;
    private static final int ORDER_STATUS_WAITING_SELLER_RELEASE = 20;
    private static final List<String> TRADING_PREFERENCE_FIELDS = List.of(
            "hasUnPostAd",
            "isKyc",
            "isEmail",
            "isMobile",
            "hasRegisterTime",
            "registerTimeThreshold",
            "orderFinishNumberDay30",
            "completeRateDay30",
            "nationalLimit",
            "hasOrderFinishNumberDay30",
            "hasCompleteRateDay30",
            "hasNationalLimit"
    );

    private final BybitProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient httpClient;
    private final Object rateLimitMonitor = new Object();
    private long nextRequestAtNanos;

    public HttpBybitGateway(BybitProperties properties, Clock clock) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public BybitReadiness checkReadiness() {
        if (!isConfigured()) {
            return new BybitReadiness(
                    false,
                    "CONFIG_MISSING",
                    "Bybit API key, secret or managed ad id is not configured",
                    null
            );
        }
        BigDecimal availableUsdt = null;
        try {
            availableUsdt = fetchAvailableUsdtBalance();
            if (StringUtils.hasText(properties.getP2pAdId())) {
                getManagedAdDetails(properties.getP2pAdId());
            }
            return new BybitReadiness(true, "HTTP", "Bybit HTTP gateway is available", availableUsdt);
        } catch (Exception exception) {
            return new BybitReadiness(false, "HTTP", exception.getMessage(), availableUsdt);
        }
    }

    @Override
    public BigDecimal fetchReferenceRate() {
        return fetchReferenceRate(properties.getRateSourceAdIndex());
    }

    @Override
    public BigDecimal fetchReferenceRate(int adIndex) {
        if (adIndex < 1) {
            throw new BybitApiException("Bybit reference ad index must be positive");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tokenId", properties.getRateSourceAsset());
        request.put("currencyId", properties.getRateSourceFiat());
        request.put("side", onlineAdSideCode(properties.getRateSourceSide()));
        request.put("page", "1");
        request.put("size", String.valueOf(adIndex));
        request.put("amount", decimal(properties.getRateSourceAmount()));

        List<String> paymentMethods = paymentMethodCodes(properties.getRateSourcePaymentMethod());
        if (!paymentMethods.isEmpty()) {
            request.put("payment", paymentMethods);
        }

        JsonNode result = post("/v5/p2p/item/online", request);
        JsonNode items = result.path("items");
        if (!items.isArray() || items.size() < adIndex) {
            throw new BybitApiException("Bybit P2P rate source does not contain ad #" + adIndex);
        }

        String price = items.get(adIndex - 1).path("price").asText();
        return parsePositiveDecimal(price, "Bybit reference price");
    }

    @Override
    public BigDecimal fetchAvailableUsdtBalance() {
        String query = queryString(new LinkedHashMap<>() {{
            put("accountType", properties.getBalanceAccountType());
            put("coin", properties.getBalanceCoin());
        }});
        JsonNode result = get("/v5/asset/transfer/query-account-coins-balance", query);
        JsonNode balances = result.path("balance");
        if (!balances.isArray() || balances.isEmpty()) {
            throw new BybitApiException("Bybit balance response does not contain " + properties.getBalanceCoin());
        }

        JsonNode balance = balances.get(0);
        String transferBalance = balance.path("transferBalance").asText();
        if (StringUtils.hasText(transferBalance)) {
            return new BigDecimal(transferBalance);
        }
        return new BigDecimal(balance.path("walletBalance").asText("0"));
    }

    @Override
    public List<BybitP2pOrder> fetchActiveOrders() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", null);
        request.put("beginTime", null);
        request.put("endTime", null);
        request.put("tokenId", properties.getBalanceCoin());
        request.put("side", orderSideCode(properties.getOrderSourceSide()));
        request.put("page", 1);
        request.put("size", Math.min(Math.max(1, properties.getOrderPageSize()), 30));

        JsonNode result = post("/v5/p2p/order/pending/simplifyList", request);
        JsonNode items = result.path("items");
        if (!items.isArray()) {
            return List.of();
        }

        List<BybitP2pOrder> orders = new ArrayList<>();
        for (JsonNode item : items) {
            String orderId = item.path("id").asText();
            if (!StringUtils.hasText(orderId)) {
                continue;
            }
            orders.add(toOrder(item));
        }
        return List.copyOf(orders);
    }

    @Override
    public Optional<BybitP2pOrder> fetchOrder(String bybitOrderId) {
        if (!StringUtils.hasText(bybitOrderId)) {
            return Optional.empty();
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderId", bybitOrderId);
        JsonNode result = post("/v5/p2p/order/info", request);
        if (!StringUtils.hasText(result.path("id").asText())) {
            return Optional.empty();
        }
        return Optional.of(toOrder(result));
    }

    @Override
    public void updateManagedAd(AdUpdateCommand command) {
        if (!command.published()) {
            unpublishManagedAd(command.bybitAdId());
            return;
        }

        JsonNode details = getManagedAdDetails(command.bybitAdId());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", command.bybitAdId());
        request.put("priceType", details.path("priceType").asText("0"));
        request.put("premium", premium(details));
        request.put("price", decimal(command.rate()));
        request.put("minAmount", decimal(command.minRub()));
        request.put("maxAmount", decimal(command.maxRub()));
        request.put("remark", command.description());
        request.put("tradingPreferenceSet", tradingPreferenceSet(details));
        request.put("paymentIds", paymentIds(details));
        request.put("actionType", adActionType(details));
        request.put("quantity", decimal(command.quantityUsdt()));
        request.put("paymentPeriod", details.path("paymentPeriod").asText("15"));
        post("/v5/p2p/item/update", request);
    }

    @Override
    public void unpublishManagedAd(String bybitAdId) {
        if (!StringUtils.hasText(bybitAdId)) {
            throw new BybitApiException("Bybit managed ad id is not configured");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("itemId", bybitAdId);
        post("/v5/p2p/item/cancel", request);
    }

    @Override
    public void sendChatMessage(String bybitOrderId, int messageIndex, String messageText) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("message", messageText);
        request.put("contentType", "str");
        request.put("orderId", bybitOrderId);
        request.put("msgUuid", UUID.nameUUIDFromBytes((bybitOrderId + ":" + messageIndex).getBytes(StandardCharsets.UTF_8)).toString());
        post("/v5/p2p/order/message/send", request);
    }

    @Override
    public void requestCancel(String bybitOrderId) {
        if (!StringUtils.hasText(bybitOrderId)) {
            throw new BybitApiException("Bybit order id is required for seller cancel request");
        }
        if (!StringUtils.hasText(properties.getCancelReasonCode())) {
            throw new BybitApiException("Bybit seller cancel reason code is not configured");
        }
        if (!StringUtils.hasText(properties.getWebSessionCookie())) {
            throw new BybitApiException("Bybit seller cancel submit endpoint was found only in the web api2 client. "
                    + "Configure BYBIT_WEB_SESSION_COOKIE to enable experimental /fiat/otc seller cancel requests.");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderId", bybitOrderId);
        if (StringUtils.hasText(properties.getCancelSubReasonCode())) {
            request.put("subCancelReasonCode", properties.getCancelSubReasonCode());
        } else {
            request.put("subCancelReasonCode", null);
        }
        request.put("cancelReasonCode", properties.getCancelReasonCode());
        request.put("cancelRemark", properties.getCancelRemark() == null ? "" : properties.getCancelRemark());
        postWebSession(SELLER_CANCEL_ENDPOINT, request);
    }

    @Override
    public void releaseOrder(String bybitOrderId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderId", bybitOrderId);
        post("/v5/p2p/order/finish", request);
    }

    private JsonNode getManagedAdDetails(String bybitAdId) {
        if (!StringUtils.hasText(bybitAdId)) {
            throw new BybitApiException("Bybit managed ad id is not configured");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("itemId", bybitAdId);
        return post("/v5/p2p/item/info", request);
    }

    private JsonNode get(String path, String queryString) {
        return sendWithRetry("GET", path, queryString, "");
    }

    private JsonNode post(String path, Map<String, Object> requestBody) {
        try {
            return sendWithRetry("POST", path, "", objectMapper.writeValueAsString(requestBody));
        } catch (JsonProcessingException exception) {
            throw new BybitApiException("Failed to serialize Bybit request body", exception);
        }
    }

    private JsonNode postWebSession(String path, Map<String, Object> requestBody) {
        try {
            return sendWebSessionWithRetry(path, objectMapper.writeValueAsString(requestBody));
        } catch (JsonProcessingException exception) {
            throw new BybitApiException("Failed to serialize Bybit web request body", exception);
        }
    }

    private JsonNode sendWithRetry(String method, String path, String queryString, String bodyJson) {
        int attempts = Math.max(1, properties.getRetryMaxAttempts());
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return send(method, path, queryString, bodyJson);
            } catch (RuntimeException exception) {
                lastException = exception;
                boolean retryable = isRetryable(exception);
                if (attempt == attempts || !retryable) {
                    break;
                }
                log.warn(
                        "Bybit API request failed, retrying: method={}, path={}, attempt={}, maxAttempts={}, message={}",
                        method,
                        path,
                        attempt,
                        attempts,
                        exception.getMessage()
                );
                sleepBeforeRetry(attempt);
            }
        }
        throw lastException == null ? new BybitApiException("Bybit request failed") : lastException;
    }

    private JsonNode sendWebSessionWithRetry(String path, String bodyJson) {
        int attempts = Math.max(1, properties.getRetryMaxAttempts());
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return sendWebSession(path, bodyJson);
            } catch (RuntimeException exception) {
                lastException = exception;
                boolean retryable = isRetryable(exception);
                if (attempt == attempts || !retryable) {
                    break;
                }
                log.warn(
                        "Bybit web API request failed, retrying: path={}, attempt={}, maxAttempts={}, message={}",
                        path,
                        attempt,
                        attempts,
                        exception.getMessage()
                );
                sleepBeforeRetry(attempt);
            }
        }
        throw lastException == null ? new BybitApiException("Bybit web request failed") : lastException;
    }

    private JsonNode send(String method, String path, String queryString, String bodyJson) {
        ensureConfigured();
        throttle();

        long timestamp = getServerSyncedTimestampMillis();
        String recvWindow = String.valueOf(properties.getRecvWindowMs());
        String payloadForSignature = "GET".equals(method) ? queryString : bodyJson;
        String signature = hmacSha256(timestamp + properties.getApiKey() + recvWindow + payloadForSignature);
        URI uri = URI.create(baseUrl() + path + (StringUtils.hasText(queryString) ? "?" + queryString : ""));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("X-BAPI-API-KEY", properties.getApiKey())
                .header("X-BAPI-TIMESTAMP", String.valueOf(timestamp))
                .header("X-BAPI-RECV-WINDOW", recvWindow)
                .header("X-BAPI-SIGN", signature);

        if ("GET".equals(method)) {
            requestBuilder.GET();
        } else {
            requestBuilder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
        }

        long startedAtNanos = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
            log.debug(
                    "Bybit API request completed: method={}, path={}, status={}, durationMs={}",
                    method,
                    path,
                    response.statusCode(),
                    durationMs
            );
            if (response.statusCode() >= 400) {
                boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                throw new BybitApiException("Bybit HTTP " + response.statusCode() + " for " + path, retryable);
            }
            JsonNode root = objectMapper.readTree(response.body());
            assertSuccess(root, path);
            return root.path("result");
        } catch (IOException exception) {
            throw new BybitApiException("Bybit request failed for " + path, exception, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BybitApiException("Bybit request interrupted for " + path, exception);
        }
    }

    private JsonNode sendWebSession(String path, String bodyJson) {
        throttle();
        URI uri = URI.create(webBaseUrl() + path);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("platform", "PC")
                .header("guid", webGuid())
                .header("Origin", "https://www.bybit.com")
                .header("Referer", "https://www.bybit.com/p2p/")
                .header("Cookie", properties.getWebSessionCookie())
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();

        long startedAtNanos = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long durationMs = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
            log.debug(
                    "Bybit web API request completed: path={}, status={}, durationMs={}",
                    path,
                    response.statusCode(),
                    durationMs
            );
            if (response.statusCode() >= 400) {
                boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                throw new BybitApiException("Bybit web HTTP " + response.statusCode() + " for " + path, retryable);
            }
            JsonNode root = objectMapper.readTree(response.body());
            assertSuccess(root, path);
            return root.path("result");
        } catch (IOException exception) {
            throw new BybitApiException("Bybit web request failed for " + path, exception, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BybitApiException("Bybit web request interrupted for " + path, exception);
        }
    }

    private boolean isRetryable(RuntimeException exception) {
        return exception instanceof BybitApiException bybitException && bybitException.isRetryable();
    }

    private void assertSuccess(JsonNode root, String path) {
        JsonNode retCodeNode = root.has("retCode") ? root.path("retCode") : root.path("ret_code");
        if (retCodeNode.isMissingNode() || retCodeNode.asInt(-1) == 0) {
            return;
        }

        String retMsg = root.has("retMsg") ? root.path("retMsg").asText() : root.path("ret_msg").asText();
        throw new BybitApiException(
                "Bybit API error for " + path + ": " + retCodeNode.asText() + " " + retMsg
        );
    }

    private String hmacSha256(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(properties.getApiSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] bytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new BybitApiException("Failed to sign Bybit request", exception);
        }
    }

    private void throttle() {
        int rps = Math.max(1, properties.getRateLimitRequestsPerSecond());
        long spacingNanos = Duration.ofSeconds(1).toNanos() / rps;
        synchronized (rateLimitMonitor) {
            long now = System.nanoTime();
            if (now < nextRequestAtNanos) {
                long sleepNanos = nextRequestAtNanos - now;
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new BybitApiException("Bybit rate limiter interrupted", exception);
                }
            }
            nextRequestAtNanos = System.nanoTime() + spacingNanos;
        }
    }

    private void sleepBeforeRetry(int attempt) {
        List<Integer> backoffs = properties.getRetryBackoffSeconds();
        int seconds = backoffs.isEmpty() ? attempt : backoffs.get(Math.min(attempt - 1, backoffs.size() - 1));
        try {
            Thread.sleep(Duration.ofSeconds(Math.max(1, seconds)).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BybitApiException("Bybit retry interrupted", exception);
        }
    }

    private String queryString(Map<String, String> params) {
        return params.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String baseUrl() {
        if (StringUtils.hasText(properties.getBaseUrl())) {
            return properties.getBaseUrl().replaceAll("/+$", "");
        }
        String env = properties.getEnv() == null ? "" : properties.getEnv().trim().toLowerCase(Locale.ROOT);
        return "mainnet".equals(env) || "prod".equals(env) || "production".equals(env)
                ? "https://api.bybit.com"
                : "https://api-testnet.bybit.com";
    }

    private String webBaseUrl() {
        if (StringUtils.hasText(properties.getWebBaseUrl())) {
            return properties.getWebBaseUrl().replaceAll("/+$", "");
        }
        return "https://api2.bybit.com";
    }

    private String webGuid() {
        if (StringUtils.hasText(properties.getWebGuid())) {
            return properties.getWebGuid();
        }
        String source = StringUtils.hasText(properties.getApiKey()) ? properties.getApiKey() : "bybit-payer";
        return UUID.nameUUIDFromBytes(("bybit-payer:" + source).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean isConfigured() {
        return StringUtils.hasText(properties.getApiKey())
                && StringUtils.hasText(properties.getApiSecret())
                && StringUtils.hasText(properties.getP2pAdId());
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new BybitApiException("Bybit API key, secret or managed ad id is not configured");
        }
    }

    private long getServerSyncedTimestampMillis() {
        long localTime = clock.millis();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/v5/market/time"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                log.debug(
                        "Bybit server time request failed, using local time: status={}",
                        response.statusCode()
                );
                return localTime;
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root.path("retCode").asInt(-1) != 0) {
                log.debug("Bybit server time response contains an error, using local time");
                return localTime;
            }
            if (root.path("time").asLong(0) > 0) {
                return root.path("time").asLong();
            }
            JsonNode result = root.path("result");
            String timeNano = result.path("timeNano").asText();
            if (StringUtils.hasText(timeNano)) {
                return Long.parseLong(timeNano) / 1_000_000L;
            }
            String timeSecond = result.path("timeSecond").asText();
            if (StringUtils.hasText(timeSecond)) {
                return Long.parseLong(timeSecond) * 1_000L;
            }
            return localTime;
        } catch (Exception exception) {
            log.debug("Bybit server time request failed, using local time: message={}", exception.getMessage());
            return localTime;
        }
    }

    private String onlineAdSideCode(String side) {
        String normalized = side == null ? "" : side.trim().toUpperCase(Locale.ROOT);
        if ("1".equals(normalized) || "BUY".equals(normalized)) {
            return "1";
        }
        if ("0".equals(normalized) || "SELL".equals(normalized)) {
            return "0";
        }
        throw new BybitApiException("Unsupported Bybit P2P online ad side: " + side);
    }

    private String orderSideCode(String side) {
        String normalized = side == null ? "" : side.trim().toUpperCase(Locale.ROOT);
        if ("0".equals(normalized) || "BUY".equals(normalized)) {
            return "0";
        }
        if ("1".equals(normalized) || "SELL".equals(normalized)) {
            return "1";
        }
        throw new BybitApiException("Unsupported Bybit P2P side: " + side);
    }

    private List<String> paymentMethodCodes(String paymentMethod) {
        if (!StringUtils.hasText(paymentMethod)) {
            return List.of();
        }

        List<String> codes = new ArrayList<>();
        for (String rawValue : paymentMethod.split(",")) {
            String value = rawValue.trim();
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String normalized = value.toUpperCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            if (value.matches("\\d+")) {
                codes.add(value);
            } else if ("BANK_TRANSFER".equals(normalized)) {
                codes.add("14");
            } else if ("KASPI_BANK".equals(normalized) || "KASPI".equals(normalized)) {
                codes.add("150");
            } else {
                codes.add(value);
            }
        }
        return List.copyOf(codes);
    }

    private BigDecimal parsePositiveDecimal(String value, String fieldName) {
        BigDecimal decimal = new BigDecimal(value);
        if (decimal.signum() <= 0) {
            throw new BybitApiException(fieldName + " must be positive");
        }
        return decimal;
    }

    private BybitP2pOrder toOrder(JsonNode item) {
        BigDecimal amountRub = decimalOrZero(item.path("amount").asText());
        BigDecimal quantityUsdt = firstDecimal(
                item.path("notifyTokenQuantity").asText(),
                item.path("quantity").asText()
        );
        if (quantityUsdt == null) {
            BigDecimal price = decimalOrZero(item.path("price").asText());
            quantityUsdt = price.signum() > 0
                    ? amountRub.divide(price, 8, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
        }
        BigDecimal feeUsdt = firstDecimal(
                item.path("fee").asText(),
                item.path("makerFee").asText(),
                item.path("takerFee").asText()
        );
        return new BybitP2pOrder(
                item.path("id").asText(),
                amountRub,
                String.valueOf(item.path("status").asInt()),
                quantityUsdt,
                feeUsdt == null ? BigDecimal.ZERO : feeUsdt
        );
    }

    private BigDecimal firstDecimal(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return decimalOrZero(value);
            }
        }
        return null;
    }

    private BigDecimal decimalOrZero(String value) {
        return StringUtils.hasText(value) ? new BigDecimal(value) : BigDecimal.ZERO;
    }

    private List<String> paymentIds(JsonNode details) {
        JsonNode paymentTerms = details.path("paymentTerms");
        if (!paymentTerms.isArray() || paymentTerms.isEmpty()) {
            throw new BybitApiException("Managed ad does not contain payment terms");
        }

        List<String> ids = new ArrayList<>();
        paymentTerms.forEach(paymentTerm -> {
            String id = paymentTerm.path("id").asText();
            if (StringUtils.hasText(id)) {
                ids.add(id);
            }
        });
        if (ids.isEmpty()) {
            throw new BybitApiException("Managed ad payment terms do not contain ids");
        }
        return List.copyOf(ids);
    }

    private String adActionType(JsonNode details) {
        return details.path("status").asInt() == AD_STATUS_ONLINE ? "MODIFY" : "ACTIVE";
    }

    private Object tradingPreferenceSet(JsonNode details) {
        JsonNode preferences = details.path("tradingPreferenceSet");
        if (preferences.isMissingNode() || preferences.isNull()) {
            return Map.of();
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        for (String field : TRADING_PREFERENCE_FIELDS) {
            JsonNode value = preferences.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                normalized.put(field, value.asText());
            }
        }
        return normalized;
    }

    private String premium(JsonNode details) {
        return "0".equals(details.path("priceType").asText("0"))
                ? ""
                : details.path("premium").asText("");
    }

    private String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

}
