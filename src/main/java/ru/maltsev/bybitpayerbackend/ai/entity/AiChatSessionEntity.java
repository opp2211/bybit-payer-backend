package ru.maltsev.bybitpayerbackend.ai.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatSessionStatus;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatStep;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatAction;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@DynamicUpdate
@Table(
        name = "ai_chat_sessions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ai_chat_sessions_withdrawal_order",
                columnNames = {"withdrawal_request_id", "bybit_order_id"}
        )
)
public class AiChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "withdrawal_request_id", nullable = false)
    private WithdrawalRequestEntity withdrawalRequest;

    @Column(name = "bybit_order_id", length = 128)
    private String bybitOrderId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 48)
    private AiChatSessionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false, length = 64)
    private AiChatStep currentStep;

    @Column(name = "auto_receipt_enabled", nullable = false)
    private boolean autoReceiptEnabled;

    @Column(name = "required_receipt_email", nullable = false)
    private boolean requiredReceiptEmail;

    @Column(name = "optional_receipt_email", nullable = false)
    private boolean optionalReceiptEmail;

    @Column(name = "sender_first_party_confirmed")
    private Boolean senderFirstPartyConfirmed;

    @Column(name = "payer_bank_confirmed")
    private Boolean payerBankConfirmed;

    @Column(name = "payer_bank_name", length = 128)
    private String payerBankName;

    @Column(name = "third_party_transfer_confirmed")
    private Boolean thirdPartyTransferConfirmed;

    @Column(name = "receipt_email_confirmed")
    private Boolean receiptEmailConfirmed;

    @Column(name = "final_warning_sent", nullable = false)
    private boolean finalWarningSent;

    @Column(name = "payment_actually_sent_claimed", nullable = false)
    private boolean paymentActuallySentClaimed;

    @Column(name = "requisites_sent_at")
    private Instant requisitesSentAt;

    @Column(name = "operator_required_at")
    private Instant operatorRequiredAt;

    @Column(name = "operator_handoff_reason", columnDefinition = "text")
    private String operatorHandoffReason;

    @Column(name = "last_processed_message_id", length = 128)
    private String lastProcessedMessageId;

    @Column(name = "last_processed_message_created_at")
    private Instant lastProcessedMessageCreatedAt;

    @Column(name = "last_receipt_check_id_handled")
    private Long lastReceiptCheckIdHandled;

    @Column(name = "last_inactivity_reminder_at")
    private Instant lastInactivityReminderAt;

    @Column(name = "payment_verification_reminder_sent_at")
    private Instant paymentVerificationReminderSentAt;

    @Column(name = "last_decision_summary", columnDefinition = "text")
    private String lastDecisionSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_action", length = 48)
    private AiChatAction lastAction;

    @Column(name = "conversation_summary", columnDefinition = "text")
    private String conversationSummary;

    @Column(name = "summary_updated_at")
    private Instant summaryUpdatedAt;

    @Column(name = "last_summarized_message_id", length = 128)
    private String lastSummarizedMessageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
