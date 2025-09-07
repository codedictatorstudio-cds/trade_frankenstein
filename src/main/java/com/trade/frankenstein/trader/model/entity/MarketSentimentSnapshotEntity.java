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
@Table(name = "market_sentiment_snapshots",
        indexes = {
                @Index(name = "ix_sentiment_asof", columnList = "asOf DESC")
        })
public class MarketSentimentSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Typically "NIFTY"
     */
    @Column(length = 64)
    private String instrumentSymbol;

    @Column(nullable = false)
    private Instant asOf;

    /**
     * 0..100
     */
    @Column(nullable = false)
    private int sentimentScore;

    /**
     * 0..100 (Confidence bar in the card)
     */
    @Column(nullable = false)
    private int confidence;

    /**
     * 0..100 (%) – “Prediction Accuracy” bar
     */
    @Column(precision = 7, scale = 4)
    private BigDecimal predictionAccuracyPct;
}
