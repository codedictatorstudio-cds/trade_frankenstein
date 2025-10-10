package com.trade.frankenstein.trader.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

@Data
public class PerformanceTrackingEvent {
    private String adviceId;
    private String instrumentKey;
    private Instant createdAt;
    private Integer enhancedScore;
    private Integer mlScore;
    private BigDecimal rlConfidence;
    private BigDecimal rlExpectedReward;
    private BigDecimal actualReturn;
    private String outcome;
}
