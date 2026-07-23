package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import java.util.List;

import ru.maltsev.bybitpayerbackend.ai.dto.AiChatAgentResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.receipt.dto.EmailReceiptCheckResponse;

public record WithdrawalDetailsResponse(
        WithdrawalResponse withdrawal,
        List<WithdrawalEventResponse> events,
        List<ChatMessageLogResponse> chatMessages,
        List<EmailReceiptCheckResponse> receiptChecks,
        AiChatAgentResponse chatAgent
) {
}
