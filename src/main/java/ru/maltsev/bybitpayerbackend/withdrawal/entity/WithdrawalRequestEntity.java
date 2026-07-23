package ru.maltsev.bybitpayerbackend.withdrawal.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.maltsev.bybitpayerbackend.bank.entity.BankEntity;
import ru.maltsev.bybitpayerbackend.user.entity.UserEntity;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalAmountMode;
import ru.maltsev.bybitpayerbackend.withdrawal.model.PayerBankType;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalMethod;
import ru.maltsev.bybitpayerbackend.withdrawal.model.WithdrawalStatus;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "withdrawal_requests")
public class WithdrawalRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", length = 7)
    private String publicId;

    @ManyToOne(optional = false, fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "workspace_id")
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserEntity createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_mode", nullable = false, length = 32)
    private WithdrawalAmountMode amountMode = WithdrawalAmountMode.FIXED;

    @Column(name = "amount_rub", precision = 19, scale = 2)
    private BigDecimal amountRub;

    @Column(name = "amount_min_rub", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountMinRub;

    @Column(name = "amount_max_rub", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountMaxRub;

    @Column(name = "recipient_phone", length = 32)
    private String recipientPhone;

    @ManyToOne
    @JoinColumn(name = "recipient_bank", referencedColumnName = "code")
    private BankEntity recipientBank;

    @Column(name = "recipient_name")
    private String recipientName;

    @Enumerated(EnumType.STRING)
    @Column(name = "withdrawal_method", nullable = false, length = 32)
    private WithdrawalMethod withdrawalMethod;

    @Column(name = "recipient_card_number", length = 32)
    private String recipientCardNumber;

    @Column(name = "recipient_account_number", length = 32)
    private String recipientAccountNumber;

    @Column(name = "recipient_card_tbank", nullable = false)
    private boolean recipientCardTbank;

    @Column(name = "third_party_transfer", nullable = false)
    private boolean thirdPartyTransfer = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "payer_bank_type", nullable = false, length = 32)
    private PayerBankType payerBankType;

    @Column(name = "require_sender_first_party", nullable = false)
    private boolean requireSenderFirstParty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 48)
    private WithdrawalStatus status;

    @Column(name = "attention_required", nullable = false)
    private boolean attentionRequired;

    @Column(name = "completion_seen", nullable = false)
    private boolean completionSeen = true;

    @Column(name = "queue_group_key", length = 64)
    private String queueGroupKey;

    @Column(name = "queue_position")
    private Integer queuePosition;

    @Column(name = "bybit_order_id", length = 128)
    private String bybitOrderId;

    @Column(name = "bybit_order_amount_rub", precision = 19, scale = 2)
    private BigDecimal bybitOrderAmountRub;

    @Column(name = "bybit_order_quantity_usdt", precision = 19, scale = 8)
    private BigDecimal bybitOrderQuantityUsdt;

    @Column(name = "bybit_order_fee_usdt", precision = 19, scale = 8)
    private BigDecimal bybitOrderFeeUsdt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "order_found_at")
    private Instant orderFoundAt;

    @Column(name = "requisites_sent_at")
    private Instant requisitesSentAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "verification_started_at")
    private Instant verificationStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "last_warning")
    private String lastWarning;
}
