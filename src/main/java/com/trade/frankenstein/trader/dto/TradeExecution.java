package com.trade.frankenstein.trader.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class TradeExecution {
    private String instrumentKey;
    private Instant timestamp;
    private BigDecimal executedPrice;
    private double quantity;
    private String side; // "BUY" or "SELL"
    private double slippage;
    private double fillRate;
}
