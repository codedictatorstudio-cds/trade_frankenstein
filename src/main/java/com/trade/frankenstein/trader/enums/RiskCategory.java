package com.trade.frankenstein.trader.enums;

/**
 * Risk categorization for advice and position management
 */
public enum RiskCategory {

    LOW("Low risk - conservative positions"),
    MEDIUM("Medium risk - balanced approach"),
    HIGH("High risk - aggressive positions"),
    CRITICAL("Critical risk - requires immediate attention");

    private final String description;

    RiskCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean requiresApproval() {
        return this == HIGH || this == CRITICAL;
    }

    public int getMaxPositionSize() {
        return switch (this) {
            case LOW -> 100;      // 1 lot max
            case MEDIUM -> 250;   // 2.5 lots max
            case HIGH -> 500;     // 5 lots max
            case CRITICAL -> 1000; // 10 lots max
        };
    }
}
