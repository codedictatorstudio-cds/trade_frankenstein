package com.trade.frankenstein.trader.common.constants;

import java.math.BigDecimal;

public interface RiskConstants {

    BigDecimal DAILY_LOSS_CAP = new BigDecimal("2500"); // ₹
    int MAX_LOTS = 4; // lots
    int ORDERS_PER_MINUTE = 6; // count per rolling 60s


    // Market hygiene
    BigDecimal MAX_SPREAD_PCT = new BigDecimal("1.50"); // %
    BigDecimal MAX_SLIPPAGE_PCT = new BigDecimal("0.75"); // %


    // Soft/hard behavior
    boolean HARD_CIRCUIT_ENABLED = true; // auto-trip on daily-loss breach
    boolean WARN_ONLY_THROTTLE = false; // if true → throttle warns instead of blocks
    boolean WARN_ONLY_SPREAD = false; // if true → spread warns instead of blocks
    boolean WARN_ONLY_SLIPPAGE = false; // if true → slippage warns instead of blocks


    // Blacklist symbols (uppercase, normalized). Edit inline as needed.
    java.util.Set<String> BLACKLIST_SYMBOLS = unmodifiableSet(
// examples: "NIFTY24SEP25000CE", "BANKNIFTY24SEP52000PE"
    );

    private static java.util.Set<String> unmodifiableSet(String... items) {
        if (items == null || items.length == 0) return java.util.Collections.emptySet();
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String s : items) if (s != null) set.add(s.trim().toUpperCase());
        return java.util.Collections.unmodifiableSet(set);
    }
}
