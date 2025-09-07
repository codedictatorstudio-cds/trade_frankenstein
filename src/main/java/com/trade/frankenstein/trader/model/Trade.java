package com.trade.frankenstein.trader.model;

import com.trade.frankenstein.trader.enums.OrderSide;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.text.DecimalFormat;

@Data
@NoArgsConstructor
public class Trade {

    private String symbol;
    private OrderSide side;
    private BigDecimal entry;
    private BigDecimal current;
    private int qty;
    private BigDecimal pnl; // signed
    private String duration;
    private String orderId;

    public Trade(String symbol, OrderSide side, double entry, double current, int qty, double pnl, String duration, String orderId) {
        this.symbol = symbol;
        this.side = side;
        this.entry = bd(entry);
        this.current = bd(current);
        this.qty = qty;
        this.pnl = bd(pnl);
        this.duration = duration;
        this.orderId = orderId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OrderSide getSide() {
        return side;
    }

    public BigDecimal getEntry() {
        return entry;
    }

    public BigDecimal getCurrent() {
        return current;
    }

    public int getQty() {
        return qty;
    }

    public BigDecimal getPnl() {
        return pnl;
    }

    public String getDuration() {
        return duration;
    }

    public String getOrderId() {
        return orderId;
    }

    // Derived for renderers
    public String getSideText() {
        return side.name();
    }

    public boolean isPnlPositive() {
        return pnl.signum() >= 0;
    }

    public String getEntryFmt() {
        return formatINR(entry);
    }

    public String getCurrentFmt() {
        return formatINR(current);
    }

    public String getPnlFmt() {
        return (pnl.signum() >= 0 ? "+" : "") + formatINR(pnl.abs());

    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static final DecimalFormat INR = new DecimalFormat("#,##,##0.00");

    private static String formatINR(BigDecimal v) {
        return "₹ " + INR.format(v);
    }
}
