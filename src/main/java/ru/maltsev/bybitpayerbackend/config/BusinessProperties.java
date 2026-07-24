package ru.maltsev.bybitpayerbackend.config;

import java.math.BigDecimal;
import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "business")
public class BusinessProperties {

    private Duration attentionTimeout = Duration.ofSeconds(300);
    private Duration chatMessageDelay = Duration.ofMillis(500);
    private Duration chatReadCacheTtl = Duration.ofSeconds(5);
    private Duration chatReadCacheMaxIdle = Duration.ofSeconds(60);
    private int chatReadCacheMaxEntries = 200;
    private int usdtQuantityScale = 4;
    private int maxPublishedAmounts = 10;
    private BigDecimal p2pFeeRate = new BigDecimal("0.00275");

}
