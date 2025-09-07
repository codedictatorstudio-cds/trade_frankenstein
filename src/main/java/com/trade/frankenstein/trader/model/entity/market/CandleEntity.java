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
@Table(name = "candles",
        uniqueConstraints = @UniqueConstraint(name = "ux_candles_symbol_open_time",
                columnNames = {"symbol", "openTime"}),
        indexes = {
                @Index(name = "ix_candles_symbol_open_time", columnList = "symbol, openTime"),
                @Index(name = "ix_candles_open_time", columnList = "openTime")
        })
public class CandleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Contract or index symbol
     */
    @Column(nullable = false, length = 64)
    private String symbol;

    /**
     * Candle open time (minute bucket, UTC)
     */
    @Column(nullable = false)
    private Instant openTime;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal openPrice;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal highPrice;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal lowPrice;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal closePrice;

    @Column
    private Long volume;
}
