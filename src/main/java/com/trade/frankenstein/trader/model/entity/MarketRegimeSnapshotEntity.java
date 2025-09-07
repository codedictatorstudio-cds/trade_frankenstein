package com.trade.frankenstein.trader.model.entity;

import com.trade.frankenstein.trader.enums.MarketRegime;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "market_regime_snapshots",
        indexes = {
                @Index(name = "ix_regime_asof", columnList = "asOf DESC")
        })
public class MarketRegimeSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Typically "NIFTY"
     */
    @Column(nullable = false, length = 64)
    private String instrumentSymbol;

    @Column(nullable = false)
    private Instant asOf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MarketRegime regime;

    /**
     * 0..100 strength (optional but handy)
     */
    @Column
    private Double strength;
}
