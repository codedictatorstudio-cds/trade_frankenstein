package com.trade.frankenstein.trader.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Forecast of near-term volatility change.
 */
@Data
@AllArgsConstructor
public class VolatilityPrediction {
    private double expectedChange;    // e.g. +0.25 = +25%
    private double confidence;        // 0..1
    private Instant asOf;             // timestamp of forecast
}
