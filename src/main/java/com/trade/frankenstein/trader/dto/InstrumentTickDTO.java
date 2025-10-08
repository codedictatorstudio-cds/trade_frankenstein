package com.trade.frankenstein.trader.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record InstrumentTickDTO(
        @NotBlank(message = "Instrument key cannot be blank")
        String instrumentKey,

        @NotNull(message = "Price cannot be null")
        @Positive(message = "Price must be positive")
        BigDecimal price,

        @NotNull(message = "Volume cannot be null")
        Long volume,

        @NotNull(message = "Timestamp cannot be null")
        Instant timestamp,

        @NotBlank(message = "Source cannot be blank")
        String source,

        @NotNull(message = "Quality flags cannot be null")
        QualityFlags qualityFlags,

        BigDecimal bidPrice,
        BigDecimal askPrice,
        BigDecimal bidSize,
        BigDecimal askSize,

        Map<String, Object> metadata
) {
    public boolean isStale(int maxAgeSeconds) {
        return timestamp.isBefore(Instant.now().minusSeconds(maxAgeSeconds));
    }

    public boolean hasQualityIssues() {
        return qualityFlags.hasAnomalies() || qualityFlags.score().doubleValue() < 0.7;
    }

    public BigDecimal getSpread() {
        if (bidPrice != null && askPrice != null) {
            return askPrice.subtract(bidPrice);
        }
        return BigDecimal.ZERO;
    }
}
