package com.trade.frankenstein.trader.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Map;

public record AlertDTO(
        String alertId,

        @NotNull(message = "Alert type cannot be null")
        AlertType type,

        @NotNull(message = "Alert severity cannot be null")
        AlertSeverity severity,

        String instrumentKey,

        @NotBlank(message = "Alert message cannot be blank")
        String message,

        @NotNull(message = "Timestamp cannot be null")
        LocalDateTime timestamp,

        String source,
        Map<String, Object> context,
        boolean acknowledged,
        String acknowledgedBy,
        LocalDateTime acknowledgedAt
) {
    public boolean isCritical() {
        return severity == AlertSeverity.CRITICAL;
    }

    public boolean requiresImmedateAction() {
        return isCritical() || type == AlertType.SYSTEM_FAILURE || type == AlertType.DATA_LOSS;
    }

    public enum AlertType {
        DATA_QUALITY_ISSUE,
        PRICE_ANOMALY,
        SYSTEM_FAILURE,
        LATENCY_HIGH,
        SIGNAL_CONFIDENCE_LOW,
        VOLATILITY_SPIKE,
        DATA_LOSS,
        API_FAILURE,
        REGIME_CHANGE
    }

    public enum AlertSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}
