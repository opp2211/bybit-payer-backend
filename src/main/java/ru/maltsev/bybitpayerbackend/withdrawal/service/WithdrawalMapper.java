package ru.maltsev.bybitpayerbackend.withdrawal.service;

import org.springframework.stereotype.Component;

import ru.maltsev.bybitpayerbackend.bybit.dto.ForeignBybitOrderResponse;
import ru.maltsev.bybitpayerbackend.bybit.entity.ForeignBybitOrderEntity;
import ru.maltsev.bybitpayerbackend.receipt.dto.EmailReceiptCheckResponse;
import ru.maltsev.bybitpayerbackend.receipt.entity.EmailReceiptCheckEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalEventResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.dto.WithdrawalResponse;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalEventEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;

@Component
public class WithdrawalMapper {

    public WithdrawalResponse toResponse(WithdrawalRequestEntity entity) {
        PayerBankType payerBankType = PayerBankType.effective(entity.getPayerBankType());
        return new WithdrawalResponse(
                entity.getId(),
                entity.getPublicId(),
                entity.getAmountRub(),
                entity.getRecipientPhone(),
                entity.getRecipientBank().getCode(),
                entity.getRecipientBank().getTitle(),
                entity.getRecipientName(),
                payerBankType.name(),
                payerBankType.getTitle(),
                payerBankType.isAutoReleaseEnabled(),
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
                entity.getCreatedBy() == null ? null : entity.getCreatedBy().getUsername(),
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
                entity.getActorType().name(),
                entity.getActor() == null ? null : entity.getActor().getUsername(),
                entity.getCreatedAt()
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
                entity.getPdfContent() != null && entity.getPdfContent().length > 0,
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
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
