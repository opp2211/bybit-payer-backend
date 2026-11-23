package ru.maltsev.bybitpayerbackend.withdrawal.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.receipt.dto.EmailReceiptCheckResponse;

public record WithdrawalDetailsResponse(
        WithdrawalResponse withdrawal,
        @Schema(types = {"string", "null"})
        String bybitCounterpartyVerifiedName,
        List<WithdrawalEventResponse> events,
        List<ChatMessageLogResponse> chatMessages,
        List<EmailReceiptCheckResponse> receiptChecks
) {
}
