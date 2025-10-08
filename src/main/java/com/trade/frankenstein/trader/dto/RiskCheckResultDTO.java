package com.trade.frankenstein.trader.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record RiskCheckResultDTO(
        boolean passed,
        String message,
        List<String> violations,
        Map<String, Object> details,
        BigDecimal riskScore,
        LocalDateTime checkedAt,
        String checkedBy,
        Map<String, BigDecimal> limits,
        Map<String, BigDecimal> currentValues
) {
    public boolean hasViolations() {
        return violations != null && !violations.isEmpty();
    }

    public boolean isHighRisk() {
        return riskScore != null && riskScore.compareTo(BigDecimal.valueOf(0.8)) >= 0;
    }

    public boolean requiresApproval() {
        return isHighRisk() || hasViolations();
    }

    public static RiskCheckResultDTO pass(String message) {
        return new RiskCheckResultDTO(
                true, message, List.of(), Map.of(),
                BigDecimal.valueOf(0.1), LocalDateTime.now(),
                "SYSTEM", Map.of(), Map.of()
        );
    }

    public static RiskCheckResultDTO fail(String message, List<String> violations) {
        return new RiskCheckResultDTO(
                false, message, violations, Map.of(),
                BigDecimal.valueOf(0.9), LocalDateTime.now(),
                "SYSTEM", Map.of(), Map.of()
        );
    }
}
