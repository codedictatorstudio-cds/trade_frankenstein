package com.trade.frankenstein.trader.model.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class TradingSignal {
    private String instrumentKey;
    private String action;      // BUY, SELL, HOLD
    private double strength;    // signal strength score
    private double riskAdjustedSize;
    private BigDecimal entryPrice;
    private BigDecimal stopLoss;
    private BigDecimal takeProfit;
    private double confidence;
}
