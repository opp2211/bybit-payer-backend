package ru.maltsev.bybitpayerbackend.bybit.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import ru.maltsev.bybitpayerbackend.bybit.model.OrderBindingStatus;
import ru.maltsev.bybitpayerbackend.workspace.entity.WorkspaceEntity;
import ru.maltsev.bybitpayerbackend.withdrawal.entity.WithdrawalRequestEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@DynamicUpdate
@Table(name = "bybit_order_bindings")
public class BybitOrderBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bybit_order_id", nullable = false, length = 128)
    private String bybitOrderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id")
    private WorkspaceEntity workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "withdrawal_request_id", nullable = false)
    private WithdrawalRequestEntity withdrawalRequest;

    @Column(name = "amount_rub", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountRub;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 48)
    private OrderBindingStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completion_thanks_requested", nullable = false)
    private boolean completionThanksRequested;

    @Setter(AccessLevel.NONE)
    @Column(name = "completion_thanks_processed", nullable = false)
    private boolean completionThanksProcessed;

    public void markReleased() {
        status = OrderBindingStatus.RELEASED;
        completionThanksRequested = true;
    }
}
