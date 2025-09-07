package com.trade.frankenstein.trader.model.entity.market;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "risk_breach_events",
        indexes = {
                @Index(name = "ix_risk_breach_asof", columnList = "asOf DESC"),
                @Index(name = "ix_risk_breach_rule", columnList = "rule"),
                @Index(name = "ix_risk_breach_corr", columnList = "correlationId")
        })
public class RiskBreachEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Event time (UTC)
     */
    @Column(nullable = false)
    private Instant asOf;

    /**
     * Short rule key, e.g., "DAILY_LOSS_CAP", "LOTS_CAP", "THROTTLE_OM"
     */
    @Column(nullable = false, length = 64)
    private String rule;

    /**
     * Measured value vs threshold at breach time (optional)
     */
    @Column(precision = 19, scale = 4)
    private BigDecimal measuredValue;

    @Column(precision = 19, scale = 4)
    private BigDecimal thresholdValue;

    /**
     * Human-readable reason (kept short for UI toasts/logs)
     */
    @Column(length = 512)
    private String reason;

    /**
     * Correlate to a request/order/decision path if available
     */
    @Column(length = 64)
    private String correlationId;

    /**
     * Optional linkable id fields (no FK to keep events append-only & resilient)
     */
    @Column(length = 64)
    private String advicePublicId;

    @Column(length = 64)
    private String orderPublicId;
}
