package ru.maltsev.bybitpayerbackend.receipt.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "ignored_email_receipts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ignored_email_receipts_withdrawal_key",
                columnNames = {"withdrawal_request_id", "receipt_key"}
        )
)
public class IgnoredEmailReceiptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "withdrawal_request_id", nullable = false)
    private WithdrawalRequestEntity withdrawalRequest;

    @Column(name = "receipt_key", nullable = false, length = 64)
    private String receiptKey;

    @Column(name = "email_message_id")
    private String emailMessageId;

    @Column(name = "pdf_filename")
    private String pdfFilename;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
