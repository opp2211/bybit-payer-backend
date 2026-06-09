package ru.maltsev.bybitpayerbackend.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "business")
public class BusinessProperties {

    private Duration attentionTimeout = Duration.ofSeconds(300);
    private Duration chatMessageDelay = Duration.ofMillis(500);
    private int usdtQuantityScale = 4;
    private int maxPublishedAmounts = 10;
    private BigDecimal p2pFeeRate = new BigDecimal("0.00275");
    private String receiptEmailToSendInChat = "opp2211@gmail.com";

    public Duration getAttentionTimeout() {
        return attentionTimeout;
    }

    public void setAttentionTimeout(Duration attentionTimeout) {
        this.attentionTimeout = attentionTimeout;
    }

    public Duration getChatMessageDelay() {
        return chatMessageDelay;
    }

    public void setChatMessageDelay(Duration chatMessageDelay) {
        this.chatMessageDelay = chatMessageDelay;
    }

    public int getUsdtQuantityScale() {
        return usdtQuantityScale;
    }

    public void setUsdtQuantityScale(int usdtQuantityScale) {
        this.usdtQuantityScale = usdtQuantityScale;
    }

    public int getMaxPublishedAmounts() {
        return maxPublishedAmounts;
    }

    public void setMaxPublishedAmounts(int maxPublishedAmounts) {
        this.maxPublishedAmounts = maxPublishedAmounts;
    }

    public BigDecimal getP2pFeeRate() {
        return p2pFeeRate;
    }

    public void setP2pFeeRate(BigDecimal p2pFeeRate) {
        this.p2pFeeRate = p2pFeeRate;
    }

    public String getReceiptEmailToSendInChat() {
        return receiptEmailToSendInChat;
    }

    public void setReceiptEmailToSendInChat(String receiptEmailToSendInChat) {
        this.receiptEmailToSendInChat = receiptEmailToSendInChat;
    }
}
