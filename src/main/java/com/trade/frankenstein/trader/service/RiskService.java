package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.constants.RiskConstants;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.model.upstox.OHLC_Quotes;
import com.trade.frankenstein.trader.model.upstox.PlaceOrderRequest;
import com.trade.frankenstein.trader.model.upstox.PortfolioResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskService {

    private final UpstoxService upstox;
    private final StreamGateway stream;

    // Rolling window for orders/min throttle (timestamps for last 60 seconds).
    private final Deque<Instant> orderTimestamps = new ArrayDeque<Instant>();

    // =================================================================================
    // Public API
    // =================================================================================

    /**
     * Lightweight, real-time risk check for a new order.
     * Uses only constants + live data; no DB writes/reads.
     */
    @Transactional(readOnly = true)
    public Result<Void> checkOrder(PlaceOrderRequest req) {
        if (req == null) return Result.fail("BAD_REQUEST", "PlaceOrderRequest is required");

        // 1) Blacklist (by trading symbol if available; else instrument_key trimmed to symbol part)
        String symbol = normalizeSymbol(extractSymbol(req));
        if (symbol != null && RiskConstants.BLACKLIST_SYMBOLS.contains(symbol)) {
            return Result.fail("SYMBOL_BLOCKED", "Symbol is blacklisted: " + symbol);
        }

        // 2) Throttle: orders per rolling minute
        double ordPct = getOrdersPerMinutePct(); // computed on the fly
        if (ordPct >= 100.0) {
            return Result.fail("THROTTLED", "Orders per minute throttle reached");
        }

        // 3) Market hygiene: live 1m bar roughness as a slippage proxy
        //    If the current 1-minute bar is too choppy, block to avoid poor fills.
        try {
            String key = extractInstrumentKey(req);
            if (key != null) {
                double roughnessPct = readLiveBarRoughnessPct(key); // ((H-L)/mid)*100
                if (!Double.isNaN(roughnessPct)) {
                    double maxSlip = RiskConstants.MAX_SLIPPAGE_PCT.doubleValue();
                    if (roughnessPct > maxSlip) {
                        return Result.fail("SLIPPAGE_HIGH", String.format(Locale.ROOT,
                                "Live bar roughness %.2f%% exceeds %.2f%%", roughnessPct, maxSlip));
                    }
                }
            }
        } catch (Throwable t) {
            // Non-fatal — prefer allowing the order instead of failing on telemetry error
            log.debug("checkOrder: slippage read failed: {}", t.getMessage());
        }

        // 4) Daily loss cap (live PnL)
        try {
            double lossNow = currentLossRupees(); // positive number if losing
            double cap = RiskConstants.DAILY_LOSS_CAP.doubleValue();
            if (lossNow >= cap - 1e-6) {
                return Result.fail("DAILY_LOSS_BREACH", "Daily loss cap reached");
            }
        } catch (Throwable t) {
            log.debug("checkOrder: PnL read failed: {}", t.getMessage());
        }

        return Result.ok(null);
    }

    /**
     * Call this AFTER a successful placeOrder to advance the rolling throttle.
     */
    public void noteOrderPlaced() {
        Instant now = Instant.now();
        synchronized (orderTimestamps) {
            orderTimestamps.addLast(now);
            evictOlderThan(now.minusSeconds(60));
        }
        // Push a fresh snapshot for the UI (non-blocking)
        try {
            stream.send("risk.summary", buildSnapshot()); // <— topic aligned to UI
        } catch (Throwable ignored) {
        }
    }

    /**
     * Real-time risk summary (ephemeral).
     * Computes PnL, throttle %, budget left, etc., from live data.
     */
    @Transactional(readOnly = true)
    public Result<RiskSnapshot> getSummary() {
        try {
            RiskSnapshot snap = buildSnapshot();
            // Fire-and-forget stream for dashboards
            try {
                stream.send("risk.summary", snap); // <— topic aligned to UI
            } catch (Throwable ignored) {
            }
            return Result.ok(snap);
        } catch (Throwable t) {
            log.error("getSummary failed", t);
            return Result.fail(t);
        }
    }

    // =================================================================================
    // Snapshot builder (no DB, no static placeholders)
    // =================================================================================

    private RiskSnapshot buildSnapshot() {
        Instant now = Instant.now();

        double cap = RiskConstants.DAILY_LOSS_CAP.doubleValue();
        double loss = 0.0;
        try {
            loss = currentLossRupees();
        } catch (Throwable ignored) {
        }

        double budgetLeft = Math.max(0.0, cap - loss);
        double dailyLossPct = (cap > 0.0) ? Math.min(100.0, (loss / cap) * 100.0) : 0.0;
        double ordersPerMinPct = getOrdersPerMinutePct();

        // lotsUsed/lotsCap without static values; cap from constants, used can be derived when available
        Integer lotsCap = Integer.valueOf(RiskConstants.MAX_LOTS);
        Integer lotsUsed = null; // derive from live positions when you have per-instrument lot sizes

        return RiskSnapshot.builder()
                .asOf(now)
                .riskBudgetLeft(budgetLeft)
                .lotsUsed(lotsUsed)
                .lotsCap(lotsCap)
                .dailyLossPct(dailyLossPct)
                .ordersPerMinPct(ordersPerMinPct)
                .build();
    }

    // =================================================================================
    // Live computations
    // =================================================================================

    /**
     * Sum of negative PnL (₹); returns a positive number for current loss.
     */
    private double currentLossRupees() {
        PortfolioResponse p = upstox.getShortTermPositions();
        if (p == null || p.getData() == null) return 0.0;

        double realised = 0.0;
        double unrealised = 0.0;
        for (Object o : p.getData()) {
            // PortfolioResponse.PortfolioData has getters (Lombok @Data) — access reflectively to be defensive
            realised += getDouble(o, "getRealised");
            unrealised += getDouble(o, "getUnrealised");
            // If your payload exposes day PnL/MTM methods, add them here similar to:
            // realised += getDouble(o, "getRealizedPnl"); unrealised += getDouble(o, "getUnrealizedPnl");
            // or prefer a specific dayPNL if present.
        }
        double pnl = realised + unrealised;   // positive = profit, negative = loss
        return (pnl < 0.0) ? -pnl : 0.0;
    }

    /**
     * Rolling 60s orders-per-minute usage as a percentage of the configured cap.
     */
    private double getOrdersPerMinutePct() {
        final int cap = Math.max(1, RiskConstants.ORDERS_PER_MINUTE);
        Instant threshold = Instant.now().minusSeconds(60);
        int count;
        synchronized (orderTimestamps) {
            evictOlderThan(threshold);
            count = orderTimestamps.size();
        }
        double pct = (count * 100.0) / cap;
        return Math.max(0.0, Math.min(100.0, pct));
    }

    /**
     * Live bar roughness % = ((high - low) / mid) * 100 for the current 1-minute bar.
     */
    private double readLiveBarRoughnessPct(String instrumentKey) {
        OHLC_Quotes q = upstox.getMarketOHLCQuote(instrumentKey, "1minute");
        if (q == null || q.getData() == null || q.getData().get(instrumentKey) == null) return Double.NaN;
        OHLC_Quotes.OHLCData d = q.getData().get(instrumentKey);
        if (d.getLive_ohlc() == null) return Double.NaN;
        OHLC_Quotes.Ohlc o = d.getLive_ohlc();
        double high = o.getHigh();
        double low = o.getLow();
        double mid = (high + low) / 2.0;
        if (mid <= 0.0 || high < low) return Double.NaN;
        return ((high - low) / mid) * 100.0;
    }

    // =================================================================================
    // Small helpers
    // =================================================================================

    private void evictOlderThan(Instant threshold) {
        while (!orderTimestamps.isEmpty()) {
            Instant head = orderTimestamps.peekFirst();
            if (head != null && head.isBefore(threshold)) {
                orderTimestamps.removeFirst();
            } else break;
        }
    }

    @Nullable
    private static String extractInstrumentKey(PlaceOrderRequest req) {
        try {
            java.lang.reflect.Method m = PlaceOrderRequest.class.getMethod("getInstrument_token");
            Object v = m.invoke(req);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static String extractSymbol(PlaceOrderRequest req) {
        // Try common fields first; fall back to instrument_key sans exchange prefix
        try {
            for (String getter : new String[]{"getTrading_symbol", "getTradingsymbol", "getSymbol"}) {
                try {
                    java.lang.reflect.Method m = PlaceOrderRequest.class.getMethod(getter);
                    Object v = m.invoke(req);
                    if (v instanceof String) {
                        String s = ((String) v).trim();
                        if (!s.isEmpty()) return s;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        String key = extractInstrumentKey(req);
        if (key == null) return null;
        int idx = key.indexOf(':');
        return (idx >= 0 && idx + 1 < key.length()) ? key.substring(idx + 1) : key;
    }

    @Nullable
    private static String normalizeSymbol(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.toUpperCase(Locale.ROOT);
    }

    private static double getDouble(Object bean, String getter) {
        try {
            java.lang.reflect.Method m = bean.getClass().getMethod(getter);
            Object v = m.invoke(bean);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {
        }
        return 0.0;
    }

    // (Optional) If you want to quickly check IST now (for logs/debugging)
    @SuppressWarnings("unused")
    private static String nowIst() {
        ZonedDateTime z = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        return z.toLocalDate() + " " + LocalTime.from(z);
    }
}
