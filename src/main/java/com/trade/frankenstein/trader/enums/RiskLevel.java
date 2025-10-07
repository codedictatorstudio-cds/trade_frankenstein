package com.trade.frankenstein.trader.enums;

/**
 * Discrete risk levels for news-driven trading decisions.
 */
public enum RiskLevel {
    LOW,        // Minimal risk
    MEDIUM,     // Moderate risk
    HIGH,       // Elevated risk
    EXTREME;    // Severe risk

    /**
     * Map a continuous risk score [0.0–1.0] to a RiskLevel.
     *
     * @param score normalized risk score (0.0 = no risk, 1.0 = maximum risk)
     * @return corresponding RiskLevel enum
     */
    public static RiskLevel fromScore(double score) {
        if (score < 0.25) {
            return LOW;
        } else if (score < 0.5) {
            return MEDIUM;
        } else if (score < 0.75) {
            return HIGH;
        } else {
            return EXTREME;
        }
    }
}
