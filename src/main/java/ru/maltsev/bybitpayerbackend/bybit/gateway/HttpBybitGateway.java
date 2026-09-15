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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;

@Component
@Profile("!local")
@Slf4j
public class HttpBybitGateway implements BybitGateway {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String MISSING_CONFIG_MESSAGE = "Bybit API key, secret, base URL or managed ad id is not configured";
    private static final int AD_STATUS_ONLINE = 10;
    private static final int ORDER_STATUS_WAITING_BUYER_PAY = 10;
    private static final int ORDER_STATUS_WAITING_SELLER_RELEASE = 20;
    private static final String CHAT_SESSION_PATH = "/v5/p2p/chat/session/getSessionId";
    private static final String CHAT_MESSAGES_PATH = "/v5/p2p/chat/message/listpage_v1";
    private static final String CHAT_SEND_PATH = "/v5/p2p/chat/message/send_v1";
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
    private final BybitCredentialsContext credentialsContext;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient httpClient;
    private final Object rateLimitMonitor = new Object();
    private final Map<ChatSessionCacheKey, String> chatSessionIds = new ConcurrentHashMap<>();
    private long nextRequestAtNanos;

    public HttpBybitGateway(BybitProperties properties, Clock clock) {
        this(properties, new BybitCredentialsContext(), clock);
    }

    @Autowired
    public HttpBybitGateway(BybitProperties properties, BybitCredentialsContext credentialsContext, Clock clock) {
        this.properties = properties;
        this.credentialsContext = credentialsContext;
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
                    MISSING_CONFIG_MESSAGE,
                    null
            );
        }
        BigDecimal availableUsdt = null;
        try {
            availableUsdt = fetchAvailableUsdtBalance();
            if (StringUtils.hasText(p2pAdId())) {
                getManagedAdDetails(p2pAdId());
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
        int pageSize = Math.min(Math.max(1, properties.getOrderPageSize()), 30);
        Map<String, BybitP2pOrder> ordersById = new LinkedHashMap<>();
        for (int page = 1; page <= 100; page++) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("status", null);
            request.put("beginTime", null);
            request.put("endTime", null);
            request.put("tokenId", properties.getBalanceCoin());
            request.put("side", orderSideCode(properties.getOrderSourceSide()));
            request.put("page", page);
            request.put("size", pageSize);

            JsonNode result = post("/v5/p2p/order/pending/simplifyList", request);
            JsonNode items = result.path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }

            for (JsonNode item : items) {
                String orderId = item.path("id").asText();
                if (!StringUtils.hasText(orderId)) {
                    continue;
                }
                ordersById.putIfAbsent(orderId, toOrder(item));
            }

            int totalCount = result.path("count").asInt(-1);
            if (items.size() < pageSize || totalCount >= 0 && ordersById.size() >= totalCount) {
                break;
            }
        }
        return List.copyOf(ordersById.values());
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
    public BybitAccountInfo fetchAccountInfo() {
        JsonNode result = post("/v5/p2p/user/personal/info", Map.of());
        return new BybitAccountInfo(
                result.path("userId").asText(),
                result.path("accountId").asText(),
                result.path("nickName").asText()
        );
    }

    @Override
    public List<BybitChatMessage> fetchChatMessages(String bybitOrderId) {
        if (!StringUtils.hasText(bybitOrderId)) {
            return List.of();
        }

        String sessionId = chatSessionId(bybitOrderId);
        int pageSize = Math.min(Math.max(1, properties.getChatMessagePageSize()), 30);
        int maxPages = Math.max(1, properties.getChatMessageMaxPages());
        List<BybitChatMessage> result = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        long lastId = 0L;

        for (int page = 1; page <= maxPages; page++) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("lastId", lastId);
            request.put("limit", pageSize);
            request.put("sessionId", sessionId);
            JsonNode messages = post(CHAT_MESSAGES_PATH, request).path("messages");
            if (!messages.isArray()) {
                return List.copyOf(result);
            }

            for (JsonNode item : messages) {
                String messageId = item.path("id").asText();
                if (seenIds.add(messageId)) {
                    result.add(toChatMessage(item, bybitOrderId));
                }
            }
            if (messages.size() < pageSize) {
                return List.copyOf(result);
            }
            long nextLastId = parseChatMessageId(messages.get(messages.size() - 1).path("id").asText());
            if (nextLastId <= 0 || nextLastId == lastId) {
                log.warn("Bybit chat message pagination stopped by invalid cursor: orderId={}, lastId={}",
                        bybitOrderId, nextLastId);
                return List.copyOf(result);
            }
            lastId = nextLastId;
        }

        log.warn(
                "Bybit chat message pagination stopped by max page limit: orderId={}, pageSize={}, maxPages={}",
                bybitOrderId,
                pageSize,
                maxPages
        );
        return List.copyOf(result);
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
    public void sendChatMessage(String bybitOrderId, String messageUuid, String messageText) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("message", messageText);
        request.put("contentType", "str");
        request.put("sessionId", chatSessionId(bybitOrderId));
        request.put("orderId", bybitOrderId);
        post(CHAT_SEND_PATH, request);
    }

    private String chatSessionId(String bybitOrderId) {
        ChatSessionCacheKey key = new ChatSessionCacheKey(apiKey(), bybitOrderId);
        return chatSessionIds.computeIfAbsent(key, ignored -> fetchChatSessionId(bybitOrderId));
    }

    private String fetchChatSessionId(String bybitOrderId) {
        Map<String, Object> orderRequest = new LinkedHashMap<>();
        orderRequest.put("orderId", bybitOrderId);
        JsonNode order = post("/v5/p2p/order/info", orderRequest);
        String targetUserMaskId = order.path("targetUserMaskId").asText();
        if (!StringUtils.hasText(targetUserMaskId)) {
            throw new BybitApiException("Bybit order does not contain targetUserMaskId: " + bybitOrderId);
        }

        Map<String, Object> sessionRequest = new LinkedHashMap<>();
        sessionRequest.put("userMaskId", targetUserMaskId);
        String sessionId = post(CHAT_SESSION_PATH, sessionRequest).path("sessionId").asText();
        if (!StringUtils.hasText(sessionId)) {
            throw new BybitApiException("Bybit chat session response does not contain sessionId: " + bybitOrderId);
        }
        return sessionId;
    }

    private BybitChatMessage toChatMessage(JsonNode item, String bybitOrderId) {
        String rawMessage = item.path("message").asText();
        JsonNode payload = parseChatMessagePayload(rawMessage);
        String message = payload.path("content").asText(rawMessage);
        Integer messageCode = payload.hasNonNull("msgCode") && StringUtils.hasText(payload.path("msgCode").asText())
                ? payload.path("msgCode").asInt()
                : null;
        return new BybitChatMessage(
                item.path("id").asText(),
                message,
                "",
                payload.path("msgType").asInt(),
                instantFromMillis(item.path("createDate").asText()),
                item.path("contentType").asText("str"),
                bybitOrderId,
                "",
                item.path("sendUserNickName").asText(),
                "",
                "",
                messageCode,
                payload.path("fileName").asText()
        );
    }

    private JsonNode parseChatMessagePayload(String rawMessage) {
        if (!StringUtils.hasText(rawMessage)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode payload = objectMapper.readTree(rawMessage);
            return payload.isObject() ? payload : objectMapper.createObjectNode();
        } catch (JsonProcessingException exception) {
            log.debug("Bybit chat message payload is not JSON");
            return objectMapper.createObjectNode();
        }
    }

    private long parseChatMessageId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return -1L;
        }
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

    private JsonNode send(String method, String path, String queryString, String bodyJson) {
        ensureConfigured();
        throttle();

        long timestamp = clock.millis();
        String recvWindow = String.valueOf(properties.getRecvWindowMs());
        String payloadForSignature = "GET".equals(method) ? queryString : bodyJson;
        String signature = hmacSha256(timestamp + apiKey() + recvWindow + payloadForSignature);
        URI uri = URI.create(baseUrl() + path + (StringUtils.hasText(queryString) ? "?" + queryString : ""));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("X-BAPI-API-KEY", apiKey())
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
            mac.init(new SecretKeySpec(apiSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
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
        String configuredBaseUrl = properties.getBaseUrl();
        if (!StringUtils.hasText(configuredBaseUrl)) {
            throw new BybitApiException("Bybit base URL is not configured");
        }
        String normalizedBaseUrl = configuredBaseUrl.trim().replaceAll("/+$", "");
        if (!StringUtils.hasText(normalizedBaseUrl)) {
            throw new BybitApiException("Bybit base URL is not configured");
        }
        return normalizedBaseUrl;
    }

    private String apiKey() {
        return credentialsContext.current()
                .map(BybitCredentials::apiKey)
                .orElse(properties.getApiKey());
    }

    private String apiSecret() {
        return credentialsContext.current()
                .map(BybitCredentials::apiSecret)
                .orElse(properties.getApiSecret());
    }

    private String p2pAdId() {
        return credentialsContext.current()
                .map(BybitCredentials::p2pAdId)
                .orElse(properties.getP2pAdId());
    }

    private boolean isConfigured() {
        return StringUtils.hasText(apiKey())
                && StringUtils.hasText(apiSecret())
                && StringUtils.hasText(properties.getBaseUrl())
                && StringUtils.hasText(p2pAdId());
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new BybitApiException(MISSING_CONFIG_MESSAGE);
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

    private Instant instantFromMillis(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            return null;
        }
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

    private record ChatSessionCacheKey(String apiKey, String bybitOrderId) {
    }

}
