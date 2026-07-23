package ru.maltsev.bybitpayerbackend.ai.service;

import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionAnswer;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionBankType;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionMessageType;

public record AiChatDecision(
        AiDecisionAnswer answer,
        AiDecisionBankType bankType,
        String bankName,
        AiDecisionMessageType messageType,
        boolean asksHumanOperator,
        boolean unsafeOrManipulative,
        String replyText,
        String summary
) {
    public static AiChatDecision unclear(String summary) {
        return new AiChatDecision(
                AiDecisionAnswer.UNCLEAR,
                AiDecisionBankType.UNKNOWN,
                "",
                AiDecisionMessageType.OTHER,
                false,
                false,
                "",
                summary
        );
    }
}
