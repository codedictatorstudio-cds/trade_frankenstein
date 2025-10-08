package com.trade.frankenstein.trader.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class TechnicalSignal {
    private String name;
    private BigDecimal value;
    private String timeframe;
    private double weight;
}
