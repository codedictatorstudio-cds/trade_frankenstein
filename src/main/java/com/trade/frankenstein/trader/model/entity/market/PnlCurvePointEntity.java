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
@Table(name = "pnl_curve_points",
        indexes = {
                @Index(name = "ix_pnlcurve_asof", columnList = "asOf"),
                @Index(name = "ix_pnlcurve_day", columnList = "dayKey, asOf")
        })
public class PnlCurvePointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * YYYYMMDD string for quick daily grouping (IST day)
     */
    @Column(nullable = false, length = 8)
    private String dayKey;

    /**
     * Snapshot instant (UTC)
     */
    @Column(nullable = false)
    private Instant asOf;

    @Column(precision = 19, scale = 2)
    private BigDecimal totalPnl;

    @Column(precision = 19, scale = 2)
    private BigDecimal dayPnl;

    @Column(precision = 19, scale = 2)
    private BigDecimal realizedPnl;

    @Column(precision = 19, scale = 2)
    private BigDecimal unrealizedPnl;
}
