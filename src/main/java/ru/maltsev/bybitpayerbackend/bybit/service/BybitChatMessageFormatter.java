package ru.maltsev.bybitpayerbackend.bybit.service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import ru.maltsev.bybitpayerbackend.bybit.config.BybitProperties;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageContentResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageContentType;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageRawResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageSenderType;
import ru.maltsev.bybitpayerbackend.bybit.gateway.BybitChatMessage;
import ru.maltsev.bybitpayerbackend.workspace.service.WorkspaceBybitIdentity;

@Component
public class BybitChatMessageFormatter {

    private final BybitProperties bybitProperties;

    public BybitChatMessageFormatter(BybitProperties bybitProperties) {
        this.bybitProperties = bybitProperties;
    }

    public ChatMessageLogResponse toResponse(
            BybitChatMessage message,
            WorkspaceBybitIdentity identity,
            Set<String> botMessageUuids
    ) {
        ChatMessageSenderType senderType = senderType(message, identity, botMessageUuids);
        return new ChatMessageLogResponse(
                "bybit:" + message.id(),
                message.orderId(),
                message.messageUuid(),
                senderType,
                authorName(message, senderType),
                content(message, senderType),
                new ChatMessageRawResponse(
                        message.messageType(),
                        message.messageCode(),
                        message.roleType(),
                        message.contentType(),
                        message.accountId(),
                        message.userId(),
                        message.nickname()
                ),
                "SENT",
                message.createdAt(),
                null
        );
    }

    public ChatMessageSenderType senderType(
            BybitChatMessage message,
            WorkspaceBybitIdentity identity,
            Set<String> botMessageUuids
    ) {
        if (message.system()) {
            return ChatMessageSenderType.SYSTEM;
        }
        if (message.support()) {
            return ChatMessageSenderType.SUPPORT;
        }
        if (ownMessage(message, identity)) {
            return botMessageUuids.contains(message.messageUuid())
                    ? ChatMessageSenderType.BOT
                    : ChatMessageSenderType.USER;
        }
        return ChatMessageSenderType.COUNTERPARTY;
    }

    public ChatMessageContentType contentType(BybitChatMessage message) {
        String rawContentType = message.contentType() == null
                ? ""
                : message.contentType().trim().toLowerCase(Locale.ROOT);
        return switch (rawContentType) {
            case "str" -> ChatMessageContentType.TEXT;
            case "pic" -> ChatMessageContentType.IMAGE;
            case "pdf" -> ChatMessageContentType.PDF;
            case "video" -> ChatMessageContentType.VIDEO;
            default -> switch (message.messageType()) {
                case 2, 6 -> ChatMessageContentType.IMAGE;
                case 7 -> ChatMessageContentType.PDF;
                case 8 -> ChatMessageContentType.VIDEO;
                default -> ChatMessageContentType.UNKNOWN;
            };
        };
    }

    private boolean ownMessage(BybitChatMessage message, WorkspaceBybitIdentity identity) {
        if (identity == null) {
            return false;
        }
        Set<String> ownIds = new HashSet<>();
        addIfPresent(ownIds, identity.userId());
        addIfPresent(ownIds, identity.accountId());
        if (!ownIds.isEmpty()) {
            return ownIds.contains(message.userId()) || ownIds.contains(message.accountId());
        }
        return StringUtils.hasText(identity.nickname()) && identity.nickname().equals(message.nickname());
    }

    private void addIfPresent(Set<String> target, String value) {
        if (StringUtils.hasText(value)) {
            target.add(value);
        }
    }

    private String authorName(BybitChatMessage message, ChatMessageSenderType senderType) {
        return switch (senderType) {
            case SYSTEM -> "Bybit";
            case SUPPORT -> StringUtils.hasText(message.nickname()) ? message.nickname() : "\u041f\u043e\u0434\u0434\u0435\u0440\u0436\u043a\u0430";
            case USER -> "\u0412\u044b";
            case BOT -> "\u0411\u043e\u0442";
            case COUNTERPARTY -> StringUtils.hasText(message.nickname()) ? message.nickname() : "\u041a\u043e\u043d\u0442\u0440\u0430\u0433\u0435\u043d\u0442";
        };
    }

    private ChatMessageContentResponse content(BybitChatMessage message, ChatMessageSenderType senderType) {
        ChatMessageContentType contentType = contentType(message);
        if (contentType == ChatMessageContentType.TEXT) {
            return new ChatMessageContentResponse(contentType, text(message.message(), senderType), null, null);
        }
        if (contentType == ChatMessageContentType.IMAGE
                || contentType == ChatMessageContentType.PDF
                || contentType == ChatMessageContentType.VIDEO) {
            return new ChatMessageContentResponse(
                    contentType,
                    null,
                    normalizeFileUrl(message.message()),
                    StringUtils.hasText(message.fileName()) ? message.fileName() : null
            );
        }
        return new ChatMessageContentResponse(
                ChatMessageContentType.UNKNOWN,
                message.message(),
                null,
                StringUtils.hasText(message.fileName()) ? message.fileName() : null
        );
    }

    private String normalizeFileUrl(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            return null;
        }
        String value = rawUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        String baseUrl = bybitProperties.getChatFileBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = "https://api2.bybit.com";
        }
        String normalizedBaseUrl = baseUrl.trim().replaceAll("/+$", "");
        return normalizedBaseUrl + (value.startsWith("/") ? value : "/" + value);
    }

    private String text(String value, ChatMessageSenderType senderType) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (senderType != ChatMessageSenderType.SYSTEM && senderType != ChatMessageSenderType.SUPPORT) {
            return value;
        }
        String withoutTags = value.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return HtmlUtils.htmlUnescape(withoutTags);
    }
}
