package com.trade.frankenstein.trader.enums;

/**
 * Enhanced AdviceStatus enum with comprehensive state management
 * for sophisticated auto trading bot operations.
 */
public enum AdviceStatus {
    // Initial states
    PENDING("Waiting for execution"),
    VALIDATED("Passed all validations"),
    QUEUED("Queued for execution"),

    // Execution states
    EXECUTING("Currently being executed"),
    EXECUTED("Successfully executed"),
    PARTIALLY_FILLED("Partially executed"),

    // Completion states
    COMPLETED("Trade completed successfully"),
    DISMISSED("Manually dismissed"),
    CANCELLED("Cancelled before execution"),

    // Error states
    FAILED("Execution failed"),
    EXPIRED("Expired before execution"),
    BLOCKED("Blocked by risk controls"),

    // Risk management states
    RISK_REJECTED("Rejected by risk system"),
    STRATEGY_INVALIDATED("Strategy conditions no longer valid"),
    MARKET_CLOSED("Market closed, pending next session"),

    // Special states
    PAUSED("Temporarily paused"),
    UNDER_REVIEW("Requires manual review"),
    SUPERSEDED("Replaced by newer advice");

    private final String description;

    AdviceStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isPending() {
        return this == PENDING || this == VALIDATED || this == QUEUED || this == PAUSED;
    }

    public boolean isActive() {
        return this == EXECUTING || this == PARTIALLY_FILLED;
    }

    public boolean isCompleted() {
        return this == EXECUTED || this == COMPLETED || this == DISMISSED ||
                this == CANCELLED || this == FAILED || this == EXPIRED;
    }

    public boolean canExecute() {
        return this == PENDING || this == VALIDATED || this == QUEUED;
    }

    public boolean isError() {
        return this == FAILED || this == BLOCKED || this == RISK_REJECTED ||
                this == STRATEGY_INVALIDATED || this == EXPIRED;
    }
}
