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
@Table(name = "bybit_managed_ad_state")
public class BybitManagedAdStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bybit_ad_id", length = 128)
    private String bybitAdId;

    @Column(name = "is_published", nullable = false)
    private boolean published;

    @Column(name = "last_rate", precision = 19, scale = 8)
    private BigDecimal lastRate;

    @Column(name = "last_rate_source_position")
    private Integer lastRateSourcePosition;

    @Column(name = "next_rate_source_position")
    private Integer nextRateSourcePosition;

    @Column(name = "reference_rate_7", precision = 19, scale = 8)
    private BigDecimal referenceRate7;

    @Column(name = "reference_rate_7_with_fee", precision = 19, scale = 8)
    private BigDecimal referenceRate7WithFee;

    @Column(name = "reference_rate_15", precision = 19, scale = 8)
    private BigDecimal referenceRate15;

    @Column(name = "last_min_rub", precision = 19, scale = 0)
    private BigDecimal lastMinRub;

    @Column(name = "last_max_rub", precision = 19, scale = 0)
    private BigDecimal lastMaxRub;

    @Column(name = "last_quantity_usdt", precision = 19, scale = 8)
    private BigDecimal lastQuantityUsdt;

    @Column(name = "last_description")
    private String lastDescription;

    @Column(name = "last_updated_at")
    private Instant lastUpdatedAt;

    @Column(name = "last_error")
    private String lastError;
}
