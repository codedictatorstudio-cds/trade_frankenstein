package com.trade.frankenstein.trader.model.dto;

import com.trade.frankenstein.trader.enums.RiskLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Represents risk assessment results for news-driven trading signals.
 */
@Getter
@Setter
public class RiskAssessment {
    private RiskLevel level;
    private double maxPositionSize;
    private double stopLossAdjustment;

    public RiskAssessment() {
        this.level = RiskLevel.MEDIUM;
        this.maxPositionSize = 1.0;
        this.stopLossAdjustment = 0.0;
    }
}
