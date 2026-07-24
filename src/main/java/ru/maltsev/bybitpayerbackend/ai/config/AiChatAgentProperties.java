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
    private Duration pollInterval = Duration.ofSeconds(5);
    private Duration inactivityReminderDelay = Duration.ofMinutes(5);
    private Duration paymentVerificationReminderDelay = Duration.ofSeconds(90);
    private int maxContextMessages = 29;
    private int retainedContextMessages = 20;
    private int maxMessagesPerDecision = 8;
    private int maxMessageLength = 1000;
}
