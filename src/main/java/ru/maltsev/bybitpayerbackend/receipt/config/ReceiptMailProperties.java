package ru.maltsev.bybitpayerbackend.receipt.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public boolean isOnlyUnread() {
        return onlyUnread;
    }

    public void setOnlyUnread(boolean onlyUnread) {
        this.onlyUnread = onlyUnread;
    }

    public boolean isMarkSeenOnValid() {
        return markSeenOnValid;
    }

    public void setMarkSeenOnValid(boolean markSeenOnValid) {
        this.markSeenOnValid = markSeenOnValid;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public void setSubjectPrefix(String subjectPrefix) {
        this.subjectPrefix = subjectPrefix;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
