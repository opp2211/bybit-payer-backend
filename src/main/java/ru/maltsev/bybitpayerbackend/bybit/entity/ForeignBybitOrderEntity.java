package ru.maltsev.bybitpayerbackend.bybit.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "foreign_bybit_orders")
public class ForeignBybitOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bybit_order_id", nullable = false, length = 128)
    private String bybitOrderId;

    @Column(name = "amount_rub", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountRub;

    @Column(name = "bybit_status", length = 64)
    private String bybitStatus;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;

    @Column(name = "cancel_request_attempts", nullable = false)
    private int cancelRequestAttempts;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "attention_required", nullable = false)
    private boolean attentionRequired = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_error")
    private String lastError;
}
