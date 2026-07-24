package ru.maltsev.bybitpayerbackend.ai.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class AiChatPromptProvider {

    private final String systemPrompt;

    public AiChatPromptProvider() {
        ClassPathResource resource = new ClassPathResource("ai/chat-agent-system-prompt.md");
        try (var input = resource.getInputStream()) {
            this.systemPrompt = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load AI chat agent system prompt", exception);
        }
    }

    public String systemPrompt() {
        return systemPrompt;
    }
}
