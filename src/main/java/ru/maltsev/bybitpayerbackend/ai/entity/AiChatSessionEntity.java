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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatSessionStatus;
import ru.maltsev.bybitpayerbackend.ai.model.AiChatStep;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "ai_chat_sessions")
public class AiChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceEntity workspace;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
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

    @Column(name = "final_warning_confirmed")
    private Boolean finalWarningConfirmed;

    @Column(name = "requisites_sent_at")
    private Instant requisitesSentAt;

    @Column(name = "operator_required_at")
    private Instant operatorRequiredAt;

    @Column(name = "last_processed_message_id", length = 128)
    private String lastProcessedMessageId;

    @Column(name = "last_processed_message_created_at")
    private Instant lastProcessedMessageCreatedAt;

    @Column(name = "last_receipt_check_id_handled")
    private Long lastReceiptCheckIdHandled;

    @Column(name = "unclear_replies_count", nullable = false)
    private int unclearRepliesCount;

    @Column(name = "cancellation_replies_count", nullable = false)
    private int cancellationRepliesCount;

    @Column(name = "paid_without_receipt_replies_count", nullable = false)
    private int paidWithoutReceiptRepliesCount;

    @Column(name = "suggested_messages_json")
    private String suggestedMessagesJson;

    @Column(name = "suggested_reason")
    private String suggestedReason;

    @Column(name = "suggested_at")
    private Instant suggestedAt;

    @Column(name = "last_decision_summary")
    private String lastDecisionSummary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
