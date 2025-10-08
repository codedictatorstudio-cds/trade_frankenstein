package com.trade.frankenstein.trader.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CandleDTO(
        @NotBlank(message = "Instrument key cannot be blank")
        String instrumentKey,

        @NotNull(message = "Open price cannot be null")
        BigDecimal open,

        @NotNull(message = "High price cannot be null")
        BigDecimal high,

        @NotNull(message = "Low price cannot be null")
        BigDecimal low,

        @NotNull(message = "Close price cannot be null")
        BigDecimal close,

        @NotNull(message = "Volume cannot be null")
        Long volume,

        @NotNull(message = "Timestamp cannot be null")
        LocalDateTime timestamp,

        @NotBlank(message = "Timeframe cannot be blank")
        String timeframe,

        @NotBlank(message = "Source cannot be blank")
        String source,

        BigDecimal vwap,
        Integer tradeCount,
        QualityFlags qualityFlags
) {
    public BigDecimal getRange() {
        return high.subtract(low);
    }

    public BigDecimal getBodySize() {
        return close.subtract(open).abs();
    }

    public boolean isBullish() {
        return close.compareTo(open) > 0;
    }

    public boolean isBearish() {
        return close.compareTo(open) < 0;
    }

    public boolean isDoji() {
        BigDecimal bodyPercent = getBodySize().divide(getRange(), 4, java.math.RoundingMode.HALF_UP);
        return bodyPercent.compareTo(BigDecimal.valueOf(0.1)) <= 0;
    }

    public BigDecimal getTrueRange(CandleDTO previousCandle) {
        if (previousCandle == null) return getRange();

        BigDecimal tr1 = high.subtract(low);
        BigDecimal tr2 = high.subtract(previousCandle.close()).abs();
        BigDecimal tr3 = low.subtract(previousCandle.close()).abs();

        return tr1.max(tr2).max(tr3);
    }
}
