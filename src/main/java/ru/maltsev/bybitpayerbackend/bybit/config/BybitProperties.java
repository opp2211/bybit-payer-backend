package ru.maltsev.bybitpayerbackend.bybit.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bybit")
public class BybitProperties {

    private boolean enabled;
    private String apiKey;
    private String apiSecret;
    private String env = "testnet";
    private String baseUrl;
    private String webBaseUrl = "https://api2.bybit.com";
    private String webSessionCookie;
    private String webGuid;
    private long recvWindowMs = 5000;
    private String p2pAdId;
    private Duration p2pPollInterval = Duration.ofSeconds(5);
    private int rateLimitRequestsPerSecond = 10;
    private int retryMaxAttempts = 3;
    private List<Integer> retryBackoffSeconds = List.of(1, 2, 4);
    private BigDecimal defaultMinRub = new BigDecimal("1000");
    private BigDecimal defaultMaxRub = new BigDecimal("10000");
    private String rateSourceSide = "BUY";
    private String rateSourceAsset = "USDT";
    private String rateSourceFiat = "RUB";
    private String rateSourcePaymentMethod = "Bank transfer";
    private BigDecimal rateSourceAmount = new BigDecimal("10000");
    private int rateSourceAdIndex = 15;
    private String orderSourceSide = "SELL";
    private int orderPageSize = 30;
    private String cancelReasonCode = "sellerOrderCancelReason_sellerOther";
    private String cancelSubReasonCode;
    private String cancelRemark = "";
    private String balanceAccountType = "FUND";
    private String balanceCoin = "USDT";
    private Local local = new Local();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public void setWebBaseUrl(String webBaseUrl) {
        this.webBaseUrl = webBaseUrl;
    }

    public String getWebSessionCookie() {
        return webSessionCookie;
    }

    public void setWebSessionCookie(String webSessionCookie) {
        this.webSessionCookie = webSessionCookie;
    }

    public String getWebGuid() {
        return webGuid;
    }

    public void setWebGuid(String webGuid) {
        this.webGuid = webGuid;
    }

    public long getRecvWindowMs() {
        return recvWindowMs;
    }

    public void setRecvWindowMs(long recvWindowMs) {
        this.recvWindowMs = recvWindowMs;
    }

    public String getP2pAdId() {
        return p2pAdId;
    }

    public void setP2pAdId(String p2pAdId) {
        this.p2pAdId = p2pAdId;
    }

    public Duration getP2pPollInterval() {
        return p2pPollInterval;
    }

    public void setP2pPollInterval(Duration p2pPollInterval) {
        this.p2pPollInterval = p2pPollInterval;
    }

    public int getRateLimitRequestsPerSecond() {
        return rateLimitRequestsPerSecond;
    }

    public void setRateLimitRequestsPerSecond(int rateLimitRequestsPerSecond) {
        this.rateLimitRequestsPerSecond = rateLimitRequestsPerSecond;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public List<Integer> getRetryBackoffSeconds() {
        return retryBackoffSeconds;
    }

    public void setRetryBackoffSeconds(List<Integer> retryBackoffSeconds) {
        this.retryBackoffSeconds = retryBackoffSeconds;
    }

    public BigDecimal getDefaultMinRub() {
        return defaultMinRub;
    }

    public void setDefaultMinRub(BigDecimal defaultMinRub) {
        this.defaultMinRub = defaultMinRub;
    }

    public BigDecimal getDefaultMaxRub() {
        return defaultMaxRub;
    }

    public void setDefaultMaxRub(BigDecimal defaultMaxRub) {
        this.defaultMaxRub = defaultMaxRub;
    }

    public String getRateSourceSide() {
        return rateSourceSide;
    }

    public void setRateSourceSide(String rateSourceSide) {
        this.rateSourceSide = rateSourceSide;
    }

    public String getRateSourceAsset() {
        return rateSourceAsset;
    }

    public void setRateSourceAsset(String rateSourceAsset) {
        this.rateSourceAsset = rateSourceAsset;
    }

    public String getRateSourceFiat() {
        return rateSourceFiat;
    }

    public void setRateSourceFiat(String rateSourceFiat) {
        this.rateSourceFiat = rateSourceFiat;
    }

    public String getRateSourcePaymentMethod() {
        return rateSourcePaymentMethod;
    }

    public void setRateSourcePaymentMethod(String rateSourcePaymentMethod) {
        this.rateSourcePaymentMethod = rateSourcePaymentMethod;
    }

    public BigDecimal getRateSourceAmount() {
        return rateSourceAmount;
    }

    public void setRateSourceAmount(BigDecimal rateSourceAmount) {
        this.rateSourceAmount = rateSourceAmount;
    }

    public int getRateSourceAdIndex() {
        return rateSourceAdIndex;
    }

    public void setRateSourceAdIndex(int rateSourceAdIndex) {
        this.rateSourceAdIndex = rateSourceAdIndex;
    }

    public String getOrderSourceSide() {
        return orderSourceSide;
    }

    public void setOrderSourceSide(String orderSourceSide) {
        this.orderSourceSide = orderSourceSide;
    }

    public int getOrderPageSize() {
        return orderPageSize;
    }

    public void setOrderPageSize(int orderPageSize) {
        this.orderPageSize = orderPageSize;
    }

    public String getCancelReasonCode() {
        return cancelReasonCode;
    }

    public void setCancelReasonCode(String cancelReasonCode) {
        this.cancelReasonCode = cancelReasonCode;
    }

    public String getCancelSubReasonCode() {
        return cancelSubReasonCode;
    }

    public void setCancelSubReasonCode(String cancelSubReasonCode) {
        this.cancelSubReasonCode = cancelSubReasonCode;
    }

    public String getCancelRemark() {
        return cancelRemark;
    }

    public void setCancelRemark(String cancelRemark) {
        this.cancelRemark = cancelRemark;
    }

    public String getBalanceAccountType() {
        return balanceAccountType;
    }

    public void setBalanceAccountType(String balanceAccountType) {
        this.balanceAccountType = balanceAccountType;
    }

    public String getBalanceCoin() {
        return balanceCoin;
    }

    public void setBalanceCoin(String balanceCoin) {
        this.balanceCoin = balanceCoin;
    }

    public Local getLocal() {
        return local;
    }

    public void setLocal(Local local) {
        this.local = local;
    }

    public static class Local {
        private BigDecimal referenceRate = new BigDecimal("92.31");
        private BigDecimal availableUsdt = new BigDecimal("100000");

        public BigDecimal getReferenceRate() {
            return referenceRate;
        }

        public void setReferenceRate(BigDecimal referenceRate) {
            this.referenceRate = referenceRate;
        }

        public BigDecimal getAvailableUsdt() {
            return availableUsdt;
        }

        public void setAvailableUsdt(BigDecimal availableUsdt) {
            this.availableUsdt = availableUsdt;
        }
    }
}
