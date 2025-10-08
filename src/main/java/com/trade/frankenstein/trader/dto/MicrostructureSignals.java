package com.trade.frankenstein.trader.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Market microstructure indicators.
 */
@Data
@AllArgsConstructor
public class MicrostructureSignals {

    private BigDecimal bidAskSpread;    // current spread %
    private BigDecimal orderBookImbalance; // (bidSize – askSize)/(bidSize+askSize)
    private BigDecimal tradeSizeSkew;   // average size bias
    private double depthScore;          // normalized depth metric
    private double priceImpact;         // predicted price impact of large orders
    private Instant asOf;               // timestamp of metrics snapshot
}
