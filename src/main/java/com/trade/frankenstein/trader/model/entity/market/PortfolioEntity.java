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
@Table(name = "portfolio",
        indexes = {
                @Index(name = "ix_portfolio_updated", columnList = "updatedAt")
        })
public class PortfolioEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Single-user bot: keep one row; can use accountId if needed
     */
    @Column(length = 64)
    private String accountId;

    /**
     * Aggregates across positions
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal totalInvested;

    @Column(precision = 19, scale = 2)
    private BigDecimal currentValue;

    @Column(precision = 19, scale = 2)
    private BigDecimal realizedPnl;

    @Column(precision = 19, scale = 2)
    private BigDecimal unrealizedPnl;

    /**
     * Convenience daily fields
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal dayPnl;

    @Column(precision = 7, scale = 4)
    private BigDecimal dayPnlPct;

    @Column(nullable = false)
    private Instant updatedAt;
}
