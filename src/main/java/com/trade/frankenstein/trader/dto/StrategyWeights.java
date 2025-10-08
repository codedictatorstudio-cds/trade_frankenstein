package com.trade.frankenstein.trader.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Holds weight parameters for strategy-specific decision scoring.
 */
@Data
@AllArgsConstructor
public class StrategyWeights {

    private double ws; // sentiment weight
    private double wr; // regime weight
    private double wm; // momentum weight
}
