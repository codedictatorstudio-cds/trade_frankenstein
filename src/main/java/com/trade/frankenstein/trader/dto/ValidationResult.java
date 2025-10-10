package com.trade.frankenstein.trader.dto;

import com.trade.frankenstein.trader.enums.ValidationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ValidationResult for Greeks and option pricing validation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {

    private ValidationStatus status;
    private List<String> reasons;
    private LocalDateTime validationTime;
    private String validatorVersion;
    private Double confidenceScore; // 0-1 confidence in validation result

    /**
     * Check if validation passed
     */
    public boolean isPassed() {
        return status == ValidationStatus.PASSED;
    }

    /**
     * Check if validation has warnings but not critical failures
     */
    public boolean hasWarnings() {
        return status == ValidationStatus.WARNING;
    }

    /**
     * Check if validation failed critically
     */
    public boolean isFailed() {
        return status == ValidationStatus.FAILED;
    }

    /**
     * Add a validation reason
     */
    public void addReason(String reason) {
        if (reasons == null) {
            reasons = new java.util.ArrayList<>();
        }
        reasons.add(reason);
    }

    /**
     * Get summary of validation issues
     */
    public String getSummary() {
        if (reasons == null || reasons.isEmpty()) {
            return "No issues";
        }
        return String.join("; ", reasons);
    }
}