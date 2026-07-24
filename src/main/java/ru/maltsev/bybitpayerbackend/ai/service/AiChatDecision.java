package ru.maltsev.bybitpayerbackend.ai.service;

import java.util.List;

import ru.maltsev.bybitpayerbackend.ai.model.AiChatAction;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatConfirmation;
import ru.maltsev.bybitpayerbackend.ai.model.AiDecisionBankType;
import ru.maltsev.bybitpayerbackend.ai.model.AiPaymentClaim;

public record AiChatDecision(
        AiChatAction action,
        List<String> messages,
        String finalWarning,
        AiChatConfirmation firstParty,
        AiDecisionBankType payerBankType,
        String payerBankName,
        AiChatConfirmation receiptEmail,
        AiChatConfirmation thirdPartyTransfer,
        AiPaymentClaim paymentClaim,
        String handoffReason,
        String summary
) {
}
