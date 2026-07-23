package ru.maltsev.bybitpayerbackend.ai.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ai.chat-agent")
public class AiChatAgentProperties {

    private boolean enabled = true;
    private boolean dryRunByDefault = false;
    private Duration pollInterval = Duration.ofSeconds(5);
    private int maxUnclearRepliesPerStep = 3;
    private int maxCancellationReplies = 8;
    private int maxPaidWithoutReceiptReplies = 3;
}
