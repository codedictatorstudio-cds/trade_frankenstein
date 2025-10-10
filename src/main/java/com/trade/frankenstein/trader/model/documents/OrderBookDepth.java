package com.trade.frankenstein.trader.model.documents;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderBookDepth {
    private List<PriceLevel> bids;
    private List<PriceLevel> asks;
    private BigDecimal bidVolume;
    private BigDecimal askVolume;
    private BigDecimal midPrice;
}
