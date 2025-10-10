package com.trade.frankenstein.trader.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;

@Data
public class MLRiskAssessment {
    private BigDecimal riskScore; // 0.0 to 1.0
    private Map<String, BigDecimal> riskFactors;
    private String recommendation;
    private BigDecimal confidenceLevel;
}
