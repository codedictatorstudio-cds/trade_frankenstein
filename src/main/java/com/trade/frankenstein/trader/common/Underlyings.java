package com.trade.frankenstein.trader.common;

import java.util.*;

/**
 * Canonical underlying keys used across services.
 * Upstox expects "NSE_INDEX|Nifty 50" for the NIFTY index.
 * <p>
 * Use Underlyings.NIFTY everywhere, or call normalize(...) when user/input can vary.
 */
public final class Underlyings {

    /**
     * Canonical key for NIFTY index (use this everywhere).
     */
    public static final String NIFTY = "NSE_INDEX|Nifty 50";

    /**
     * Known aliases → canonical. Keep this tiny and strict.
     */
    private static final Map<String, String> ALIASES;

    static {
        Map<String, String> m = new HashMap<>();
        // very common variants seen in code/UI/logs
        m.put("NIFTY", NIFTY);
        m.put("NIFTY50", NIFTY);
        m.put("NIFTY 50", NIFTY);
        m.put("NSE_INDEX|NIFTY 50", NIFTY);
        m.put("NSE_INDEX|Nifty50", NIFTY);
        m.put("NSE|NIFTY 50", NIFTY);
        m.put("NSE|Nifty 50", NIFTY);
        // allow plain “NSE_INDEX|Nifty 50” to pass through (normalize is idempotent)
        m.put(NIFTY.toUpperCase(Locale.ROOT), NIFTY);
        ALIASES = Collections.unmodifiableMap(m);
    }

    private Underlyings() {
    }

    /**
     * Returns the canonical underlying key for the given value.
     * If value is null/blank, defaults to NIFTY.
     */
    public static String normalize(String value) {
        if (value == null) return NIFTY;
        String v = value.trim();
        if (v.isEmpty()) return NIFTY;
        String hit = ALIASES.get(v.toUpperCase(Locale.ROOT));
        return (hit != null) ? hit : v; // if already canonical or unknown-but-valid, pass through
    }

    /**
     * True if the value refers to NIFTY (any supported alias).
     */
    public static boolean isNifty(String value) {
        return NIFTY.equals(normalize(value));
    }
}
