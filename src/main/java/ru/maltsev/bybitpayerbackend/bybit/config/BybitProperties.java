package ru.maltsev.bybitpayerbackend.bybit.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "bybit")
public class BybitProperties {

    private String apiKey;
    private String apiSecret;
    private String baseUrl;
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
    private int rateSourceMinAdIndex = 7;
    private String orderSourceSide = "SELL";
    private int orderPageSize = 30;
    private int chatMessagePageSize = 30;
    private int chatMessageMaxPages = 100;
    private String chatFileBaseUrl = "https://api2.bybit.com";
    private String balanceAccountType = "FUND";
    private String balanceCoin = "USDT";
}
