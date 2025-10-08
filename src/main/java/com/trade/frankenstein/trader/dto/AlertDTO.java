package com.trade.frankenstein.trader.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
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
        Instant timestamp,

        String source,
        Map<String, Object> context,
        boolean acknowledged,
        String acknowledgedBy,
        Instant acknowledgedAt
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
        REGIME_CHANGE,
        RISK_MANAGEMENT
    }

    public enum AlertSeverity {
        LOW, MEDIUM, HIGH, CRITICAL,WARNING, INFO
    }
}
