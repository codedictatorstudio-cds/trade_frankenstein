package com.trade.frankenstein.trader.model.entity;

import com.trade.frankenstein.trader.enums.MarketRegime;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "decision_quality_snapshots",
        indexes = {
                @Index(name = "ix_decision_quality_asof", columnList = "asOf DESC")
        })
public class DecisionQualitySnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * For NIFTY-only bot this can be "NIFTY"; keep flexible
     */
    @Column(nullable = false, length = 64)
    private String instrumentSymbol;

    @Column(nullable = false)
    private Instant asOf;

    /**
     * 0..100 score that feeds the ring in RegimeDecisionCard
     */
    @Column(nullable = false)
    private int qualityScore;

    /**
     * UI chip label: Bullish/Bearish/Neutral from MarketRegime
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MarketRegime trend;

    /**
     * Risk/Reward ratio (e.g., 1.80) for the “RR” tag
     */
    @Column(precision = 9, scale = 4)
    private BigDecimal rr;

    /**
     * Slippage (%) as fraction 0..100 for the “Slippage” tag (store % not bps)
     */
    @Column(precision = 7, scale = 4)
    private BigDecimal slippagePct;

    /**
     * Whether throttling is currently applied (feeds “Throttle” tag)
     */
    @Column(nullable = false)
    private boolean throttleOn;

    /**
     * Bullet reasons shown on the card
     */
    @ElementCollection
    @CollectionTable(name = "decision_quality_reasons", joinColumns = @JoinColumn(name = "snapshot_id"))
    @Column(name = "reason", length = 512)
    private List<String> reasons;
}
