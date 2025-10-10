package com.trade.frankenstein.trader.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;

@Data
public class MLFeatures {
    private BigDecimal momentumScore;
    private String volatilityRegime; // LOW, MEDIUM, HIGH
    private BigDecimal trendStrength;
    private BigDecimal seasonalAdjustment;
    private Map<String, Double> technicalFeatures;
    private Map<String, Double> fundamentalFeatures;
    private Map<String, Double> sentimentFeatures;
}
