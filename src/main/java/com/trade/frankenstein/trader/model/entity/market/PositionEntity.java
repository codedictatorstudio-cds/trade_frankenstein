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
@Table(name = "positions",
        indexes = {
                @Index(name = "ix_positions_contract", columnList = "contract_id"),
                @Index(name = "ix_positions_open", columnList = "open"),
                @Index(name = "ix_positions_updated", columnList = "updatedAt")
        })
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Link to the traded NIFTY option contract
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id")
    private OptionContractEntity contract;

    /**
     * Net quantity (positive for long, negative for short; we mostly buy options)
     */
    @Column(nullable = false)
    private Integer quantity;

    /**
     * Average entry price for the net position
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal avgPrice;

    /**
     * Invested value for convenience
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal invested;

    /**
     * Last computed unrealized PnL (snapshotted periodically)
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal unrealizedPnl;

    /**
     * Mark if position has been fully squared off
     */
    @Column(nullable = false)
    private boolean open;

    /**
     * Last update instant
     */
    @Column(nullable = false)
    private Instant updatedAt;
}
