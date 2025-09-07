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
@Table(name = "ticks",
        indexes = {
                @Index(name = "ix_ticks_symbol_ts", columnList = "symbol, ts DESC"),
                @Index(name = "ix_ticks_ts", columnList = "ts DESC")
        })
public class TickEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Contract or index symbol, e.g., "NIFTY24SEP24950CE" or "NIFTY"
     */
    @Column(nullable = false, length = 64)
    private String symbol;

    /**
     * Event time (UTC)
     */
    @Column(nullable = false)
    private Instant ts;

    /**
     * Last traded price
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal ltp;

    /**
     * Trade quantity/volume for the tick if available (optional)
     */
    @Column
    private Long qty;
}
