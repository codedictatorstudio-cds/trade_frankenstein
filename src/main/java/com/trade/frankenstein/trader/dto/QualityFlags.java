package com.trade.frankenstein.trader.dto;

import java.math.BigDecimal;
import java.util.Set;

public record QualityFlags(
        BigDecimal score, // 0.0 to 1.0
        boolean hasGaps,
        boolean hasSpikes,
        boolean isStale,
        boolean hasLatencyIssues,
        Set<String> anomalies,
        long latencyMs,
        String validationStatus
) {
    public boolean hasAnomalies() {
        return hasGaps || hasSpikes || isStale || hasLatencyIssues || !anomalies.isEmpty();
    }

    public boolean isHighQuality() {
        return score.compareTo(BigDecimal.valueOf(0.9)) >= 0 && !hasAnomalies();
    }

    public boolean isAcceptable() {
        return score.compareTo(BigDecimal.valueOf(0.7)) >= 0;
    }

    public static QualityFlags perfect() {
        return new QualityFlags(
                BigDecimal.ONE, false, false, false, false,
                Set.of(), 0L, "VALIDATED"
        );
    }

    public static QualityFlags poor(String reason) {
        return new QualityFlags(
                BigDecimal.valueOf(0.3), false, false, false, false,
                Set.of(reason), 0L, "FAILED"
        );
    }
}
