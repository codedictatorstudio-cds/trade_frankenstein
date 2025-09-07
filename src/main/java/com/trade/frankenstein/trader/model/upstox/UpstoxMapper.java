package com.trade.frankenstein.trader.model.upstox;


import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.OrderStatus;
import com.trade.frankenstein.trader.enums.OrderType;

import java.util.Locale;

public final class UpstoxMapper {

    private UpstoxMapper() {
    }

    public static String mapOrderType(OrderType t) {
        switch (t) {
            case MARKET:
                return "MARKET";
            case LIMIT:
                return "LIMIT";
            case STOP_MARKET:
                return "SL-M"; // Stop-Loss Market
            case STOP_LIMIT:
                return "SL";   // Stop-Loss Limit
            default:
                throw new IllegalArgumentException("Unsupported OrderType: " + t);
        }
    }

    public static OrderType parseOrderType(String s) {
        if (s == null) return OrderType.MARKET;
        switch (s.toUpperCase(Locale.ROOT)) {
            case "MARKET":
                return OrderType.MARKET;
            case "LIMIT":
                return OrderType.LIMIT;
            case "SL-M":
                return OrderType.STOP_MARKET;
            case "SL":
                return OrderType.STOP_LIMIT;
            default:
                return OrderType.MARKET;
        }
    }

    public static String mapSide(OrderSide s) {
        switch (s) {
            case BUY:
                return "BUY";
            case SELL:
                return "SELL";
            default:
                throw new IllegalArgumentException("Unsupported side: " + s);
        }
    }

    public static OrderSide parseSide(String s) {
        if (s == null) return OrderSide.BUY;
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "BUY" -> OrderSide.BUY;
            case "SELL" -> OrderSide.SELL;
            default -> OrderSide.BUY;
        };
    }

    public static OrderStatus parseStatus(String s) {
        if (s == null) return OrderStatus.PENDING_SUBMIT;
        String x = s.toLowerCase(Locale.ROOT);
        if (x.contains("complete") && x.contains("partial")) return OrderStatus.PARTIALLY_FILLED;
        if (x.equals("complete")) return OrderStatus.FILLED;
        if (x.equals("cancelled") || x.equals("canceled")) return OrderStatus.CANCELLED;
        if (x.equals("rejected")) return OrderStatus.REJECTED;
        if (x.equals("expired")) return OrderStatus.EXPIRED;
        if (x.contains("open") || x.contains("pending") || x.contains("validation")) return OrderStatus.PENDING_SUBMIT;
        return OrderStatus.PENDING_SUBMIT;
    }

    public static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
