package com.trade.frankenstein.trader.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public record SignalDTO(
        @NotBlank(message = "Instrument key cannot be blank")
        String instrumentKey,

        @NotBlank(message = "Signal type cannot be blank")
        String signalType,

        @NotNull(message = "Signal value cannot be null")
        BigDecimal value,

        @NotNull(message = "Confidence cannot be null")
        BigDecimal confidence,

        @NotNull(message = "Timestamp cannot be null")
        LocalDateTime timestamp,

        String timeframe,
        String direction, // BUY, SELL, HOLD
        BigDecimal strength,
        BigDecimal probability,

        Map<String, Object> metadata,
        Map<String, BigDecimal> supportingIndicators
) {
    public boolean isHighConfidence() {
        return confidence.compareTo(BigDecimal.valueOf(0.8)) >= 0;
    }

    public boolean isActionable() {
        return isHighConfidence() &&
                strength != null &&
                strength.compareTo(BigDecimal.valueOf(0.6)) >= 0;
    }

    public SignalStrength getSignalStrength() {
        if (strength == null) return SignalStrength.UNKNOWN;

        if (strength.compareTo(BigDecimal.valueOf(0.8)) >= 0) return SignalStrength.STRONG;
        if (strength.compareTo(BigDecimal.valueOf(0.6)) >= 0) return SignalStrength.MODERATE;
        if (strength.compareTo(BigDecimal.valueOf(0.4)) >= 0) return SignalStrength.WEAK;
        return SignalStrength.VERY_WEAK;
    }

    public enum SignalStrength {
        VERY_WEAK, WEAK, MODERATE, STRONG, UNKNOWN
    }
}
