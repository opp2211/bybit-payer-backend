package ru.maltsev.bybitpayerbackend.withdrawal.service;

import org.springframework.stereotype.Component;

import ru.maltsev.bybitpayerbackend.bybit.dto.ChatMessageLogResponse;
import ru.maltsev.bybitpayerbackend.bybit.dto.ForeignBybitOrderResponse;
import ru.maltsev.bybitpayerbackend.bybit.entity.BybitChatMessageLogEntity;
import ru.maltsev.bybitpayerbackend.bybit.entity.ForeignBybitOrderEntity;
import ru.maltsev.bybitpayerbackend.receipt.dto.EmailReceiptCheckResponse;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalEventResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalEventEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;

@Component
public class WithdrawalMapper {

    public WithdrawalResponse toResponse(WithdrawalRequestEntity entity) {
        return new WithdrawalResponse(
                entity.getId(),
                entity.getAmountRub(),
                entity.getRecipientPhone(),
                entity.getRecipientBank().getCode(),
                entity.getRecipientBank().getTitle(),
                entity.getRecipientName(),
                entity.getStatus().name(),
                entity.getStatus().getTitle(),
                entity.isAttentionRequired(),
                entity.isCompletionSeen(),
                entity.getQueueGroupKey(),
                entity.getQueuePosition(),
                entity.getBybitOrderId(),
                entity.getBybitOrderAmountRub(),
                entity.getBybitOrderQuantityUsdt(),
                entity.getBybitOrderFeeUsdt(),
                totalUsdt(entity),
                entity.getCreatedAt(),
                entity.getQueuedAt(),
                entity.getPublishedAt(),
                entity.getOrderFoundAt(),
                entity.getRequisitesSentAt(),
                entity.getPaidAt(),
                entity.getVerificationStartedAt(),
                entity.getCompletedAt(),
                entity.getCancelledAt(),
                entity.getLastError(),
                entity.getLastWarning(),
                entity.getStatus().canBeCancelled(),
                entity.getStatus().canBeReleased() && entity.getBybitOrderId() != null
        );
    }

    private java.math.BigDecimal totalUsdt(WithdrawalRequestEntity entity) {
        if (entity.getBybitOrderQuantityUsdt() == null) {
            return null;
        }
        return entity.getBybitOrderQuantityUsdt().add(
                entity.getBybitOrderFeeUsdt() == null
                        ? java.math.BigDecimal.ZERO
                        : entity.getBybitOrderFeeUsdt()
        );
    }

    public WithdrawalEventResponse toEventResponse(WithdrawalEventEntity entity) {
        return new WithdrawalEventResponse(
                entity.getId(),
                entity.getEventType().name(),
                entity.getMessage(),
                entity.getPayloadJson(),
                entity.getCreatedAt()
        );
    }

    public ChatMessageLogResponse toChatMessageResponse(BybitChatMessageLogEntity entity) {
        return new ChatMessageLogResponse(
                entity.getId(),
                entity.getBybitOrderId(),
                entity.getMessageIndex(),
                entity.getMessageText(),
                entity.getStatus().name(),
                entity.getSentAt(),
                entity.getError()
        );
    }

    public EmailReceiptCheckResponse toReceiptCheckResponse(EmailReceiptCheckEntity entity) {
        return new EmailReceiptCheckResponse(
                entity.getId(),
                entity.getBybitOrderId(),
                entity.getEmailMessageId(),
                entity.getEmailFrom(),
                entity.getEmailSubject(),
                entity.getEmailReceivedAt(),
                entity.getPdfFilename(),
                entity.getParsedStatus(),
                entity.getParsedAmountRub(),
                entity.getParsedRecipientPhone(),
                entity.getParsedRecipientBank(),
                entity.getParsedRecipientName(),
                entity.getParsedOperationDate(),
                entity.getParsedOperationId(),
                entity.getParsedReceiptNumber(),
                entity.getVerificationStatus().name(),
                entity.getVerificationError(),
                entity.getCreatedAt()
        );
    }

    public ForeignBybitOrderResponse toForeignOrderResponse(ForeignBybitOrderEntity entity) {
        return new ForeignBybitOrderResponse(
                entity.getId(),
                entity.getBybitOrderId(),
                entity.getAmountRub(),
                entity.getBybitStatus(),
                entity.getReason(),
                entity.isCancelRequested(),
                entity.getCancelRequestAttempts(),
                entity.getCancelRequestedAt(),
                entity.isAttentionRequired(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastError()
        );
    }
}
