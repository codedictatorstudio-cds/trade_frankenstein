package com.trade.frankenstein.trader.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class OptimalPositionSize {
    private Integer recommendedLots;
    private Integer maxLots;
    private BigDecimal confidence;
    private BigDecimal expectedReward;
    private BigDecimal riskScore;
    private String methodology;
}
