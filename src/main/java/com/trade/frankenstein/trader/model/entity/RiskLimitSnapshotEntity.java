package com.trade.frankenstein.trader.model.entity;

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
@Table(name = "risk_limit_snapshots",
        indexes = {
                @Index(name = "ix_risk_limit_snapshots_asof", columnList = "asOf DESC")
        })
public class RiskLimitSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Snapshot time (UTC)
     */
    @Column(nullable = false)
    private Instant asOf;

    /**
     * Total daily risk budget (₹) and used so far
     */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal riskBudgetTotal;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal riskBudgetUsed;

    /**
     * Lots cap and consumed lots
     */
    @Column(nullable = false)
    private Integer lotsCap;

    @Column(nullable = false)
    private Integer lotsUsed;

    /**
     * Intraday P&L and % (for the bar in UI)
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal dayPnl;

    @Column(precision = 7, scale = 4)
    private BigDecimal dayPnlPct;

    /**
     * Orders/minute throttle metrics
     */
    @Column(nullable = false)
    private Integer ordersPerMinuteCap;

    @Column(nullable = false)
    private Integer ordersPerMinuteUsed;
}
