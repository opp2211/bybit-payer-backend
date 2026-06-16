package ru.maltsev.bybitpayerbackend.receipt.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "receipt.mail")
public class ReceiptMailProperties {

    private boolean enabled;
    private String host = "imap.gmail.com";
    private int port = 993;
    private String folder = "INBOX";
    private String sender = "noreply@tinkoff.ru";
    private boolean onlyUnread = true;
    private boolean markSeenOnValid;
    private int maxMessages = 20;
    private Duration pollInterval = Duration.ofSeconds(5);
    private Duration connectionTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(20);
    private String subjectPrefix = "Документ по операции";
    private String username;
    private String password;

}
