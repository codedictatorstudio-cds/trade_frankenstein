package com.trade.frankenstein.trader.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data@Builder
public class TradeRow {

    private String id;             // optional (handy for explain endpoint)
    private String symbol;
    private String side;           // "BUY" | "SELL"
    private BigDecimal entryInr;
    private BigDecimal currentInr;
    private int qty;
    private BigDecimal pnlInr;     // signed
    private String duration;       // e.g., "12m"
    private String orderId;
}
