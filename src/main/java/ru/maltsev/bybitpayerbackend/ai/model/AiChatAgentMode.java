package ru.maltsev.bybitpayerbackend.ai.model;

import lombok.Getter;

@Getter
public enum AiChatAgentMode {
    ENABLED("Включено"),
    DISABLED("Выключено"),
    DRY_RUN("Dry run");

    private final String title;

    AiChatAgentMode(String title) {
        this.title = title;
    }
}
