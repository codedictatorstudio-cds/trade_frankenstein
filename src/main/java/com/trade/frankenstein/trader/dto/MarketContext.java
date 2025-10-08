package com.trade.frankenstein.trader.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class MarketContext {
    private String instrumentKey;
    private BigDecimal currentPrice;
    private BigDecimal momentum5m;
    private BigDecimal momentum15m;
    private BigDecimal regime5m;
    private BigDecimal regime60m;
    private BigDecimal atrPct;
    private BigDecimal intradayRangePct;
    private Map<String, Double> alternativeFeatures;
}
