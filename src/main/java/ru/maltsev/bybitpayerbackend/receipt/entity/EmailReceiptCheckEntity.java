package ru.maltsev.bybitpayerbackend.receipt.entity;

import java.math.BigDecimal;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.maltsev.bybitpayerbackend.receipt.model.ReceiptVerificationStatus;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "email_receipt_checks")
public class EmailReceiptCheckEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "withdrawal_request_id", nullable = false)
    private WithdrawalRequestEntity withdrawalRequest;

    @Column(name = "bybit_order_id", length = 128)
    private String bybitOrderId;

    @Column(name = "email_message_id")
    private String emailMessageId;

    @Column(name = "email_from")
    private String emailFrom;

    @Column(name = "email_subject")
    private String emailSubject;

    @Column(name = "email_received_at")
    private Instant emailReceivedAt;

    @Column(name = "pdf_filename")
    private String pdfFilename;

    @Column(name = "pdf_content")
    private byte[] pdfContent;

    @Column(name = "parsed_status", length = 128)
    private String parsedStatus;

    @Column(name = "parsed_amount_rub", precision = 19, scale = 2)
    private BigDecimal parsedAmountRub;

    @Column(name = "parsed_recipient_phone", length = 64)
    private String parsedRecipientPhone;

    @Column(name = "parsed_recipient_bank", length = 128)
    private String parsedRecipientBank;

    @Column(name = "parsed_recipient_name")
    private String parsedRecipientName;

    @Column(name = "parsed_operation_date", length = 128)
    private String parsedOperationDate;

    @Column(name = "parsed_operation_id", length = 128)
    private String parsedOperationId;

    @Column(name = "parsed_receipt_number", length = 128)
    private String parsedReceiptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 48)
    private ReceiptVerificationStatus verificationStatus;

    @Column(name = "verification_error")
    private String verificationError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
