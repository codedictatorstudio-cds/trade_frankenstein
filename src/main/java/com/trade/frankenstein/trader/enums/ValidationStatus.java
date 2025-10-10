package com.trade.frankenstein.trader.enums;

/**
 * Validation status enumeration for various validation results
 */
public enum ValidationStatus {
    PASSED,     // Validation passed successfully
    WARNING,    // Validation passed with warnings/minor issues
    FAILED,     // Validation failed critically
    PENDING,    // Validation is in progress
    SKIPPED,    // Validation was skipped
    ERROR       // Validation encountered an error
}