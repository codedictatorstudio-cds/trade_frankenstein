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

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskService {

    private final UpstoxService upstox;
    private final StreamGateway stream;

    // --- Circuit config (percent or absolute; set via setters or config binder if you prefer) ---
    private BigDecimal dailyDdCapPct = BigDecimal.valueOf(3.0);
    private BigDecimal dailyDdCapAbs = BigDecimal.ZERO;
    private BigDecimal seedStartEquity = BigDecimal.ZERO;

    // --- Circuit live state (IST day roll-over safe) ---
    private final AtomicReference<LocalDate> ddDay = new AtomicReference<>(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    private final AtomicReference<BigDecimal> dayStartEquity = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicReference<BigDecimal> dayLossAbs = new AtomicReference<>(BigDecimal.ZERO); // positive number
    private final AtomicBoolean circuitTripped = new AtomicBoolean(false);

    // Rolling window for orders/min throttle (timestamps for last 60 seconds).
    private final Deque<Instant> orderTimestamps = new ArrayDeque<>();

    /**
     * Lightweight, real-time risk check for a new order.
     * Uses only constants + live data; no DB writes/reads.
     */
    @Transactional(readOnly = true)
    public Result<Void> checkOrder(PlaceOrderRequest req) {
        if (req == null) return Result.fail("BAD_REQUEST", "PlaceOrderRequest is required");

        // 0) Global kill-switch (daily circuit)
        if (isDailyCircuitTripped().orElse(false)) {
            return Result.fail("CIRCUIT_TRIPPED", "Daily risk circuit is active");
        }

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
            String key = req.getInstrument_token();
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
        } catch (Exception t) {
            // Non-fatal — prefer allowing the order instead of failing on telemetry error
            log.error("checkOrder: slippage read failed: {}", t);
        }

        // 4) Daily loss cap (live PnL)
        try {
            double lossNow = currentLossRupees(); // positive number if losing
            double cap = RiskConstants.DAILY_LOSS_CAP.doubleValue();
            if (lossNow >= cap - 1e-6) {
                return Result.fail("DAILY_LOSS_BREACH", "Daily loss cap reached");
            }
        } catch (Exception t) {
            log.error("checkOrder: PnL read failed: {}", t);
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
            stream.send("risk.summary", buildSnapshot());
        } catch (Exception ignored) {
            log.error("noteOrderPlaced: SSE send failed: {}", ignored);
        }
    }

    /**
     * Real-time risk summary (ephemeral).
     */
    @Transactional(readOnly = true)
    public Result<RiskSnapshot> getSummary() {
        try {
            RiskSnapshot snap = buildSnapshot();
            try {
                stream.send("risk.summary", snap);
            } catch (Exception ignored) {
            }
            return Result.ok(snap);
        } catch (Exception t) {
            log.error("getSummary failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Convenience: pull live PnL from broker and refresh the internal loss counter used by the circuit.
     */
    public void refreshDailyLossFromBroker() {
        try {
            BigDecimal realizedToday = upstox.getRealizedPnlToday(); // may be null/zero
            if (realizedToday == null) realizedToday = BigDecimal.ZERO;
            BigDecimal lossAbs = realizedToday.signum() < 0 ? realizedToday.abs() : BigDecimal.ZERO;
            updateDailyLossAbs(lossAbs);
        } catch (Exception t) {
            log.error("refreshDailyLossFromBroker failed: {}", t);
        }
    }


    /**
     * Read-only circuit state for Strategy/Engine/UI; also emits SSE.
     */
    public Result<Boolean> getCircuitState() {
        boolean tripped = isDailyCircuitTripped().orElse(false);
        try {
            stream.send("risk.circuit", tripped);
        } catch (Exception ignored) {
            log.error("getCircuitState: SSE send failed: {}", ignored);
        }
        return Result.ok(tripped);
    }

    /**
     * Manually trip the circuit (e.g., on regime flip/drawdown breach outside this service).
     */
    public Result<Void> tripCircuit(String reason) {
        circuitTripped.set(true);
        try {
            stream.send("risk.circuit", true);
        } catch (Exception ignored) {
            log.error("tripCircuit: SSE send failed: {}", ignored);
        }
        if (reason != null && !reason.isEmpty()) {
            log.warn("Risk circuit TRIPPED: {}", reason);
        }
        return Result.ok(null);
    }

    /**
     * Reset the circuit (e.g., after review or next day).
     */
    public Result<Void> resetCircuit() {
        circuitTripped.set(false);
        try {
            stream.send("risk.circuit", false);
        } catch (Exception ignored) {
            log.error("resetCircuit: SSE send failed: {}", ignored);
        }
        log.info("Risk circuit RESET");
        return Result.ok(null);
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
        } catch (Exception ignored) {
            log.error("buildSnapshot: PnL read failed: {}", ignored);
        }

        double budgetLeft = Math.max(0.0, cap - loss);
        double dailyLossPct = (cap > 0.0) ? Math.min(100.0, (loss / cap) * 100.0) : 0.0;
        double ordersPerMinPct = getOrdersPerMinutePct();

        Integer lotsCap = Integer.valueOf(RiskConstants.MAX_LOTS);
        Integer lotsUsed = null; // derive from live positions when lot sizes are wired

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
            // Defensive access against SDK variations
            realised += getDouble(o, "getRealised");
            unrealised += getDouble(o, "getUnrealised");
            // If your payload exposes day PnL/MTM methods, add here similarly.
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
        OHLC_Quotes q = upstox.getMarketOHLCQuote(instrumentKey, "I1");
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
        } catch (Exception ignored) {
            log.error("extractSymbol failed: {}", ignored);
        }
        String key = req.getInstrument_token();
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
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    // (Optional) If you want to quickly check IST now (for logs/debugging)
    @SuppressWarnings("unused")
    private static String nowIst() {
        ZonedDateTime z = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        return z.toLocalDate() + " " + LocalTime.from(z);
    }

    public Optional<Boolean> isDailyCircuitTripped() {
        try {
            // Reset on new trading day (IST)
            LocalDate todayIst = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            LocalDate stored = ddDay.get();
            if (stored == null || !stored.equals(todayIst)) {
                ddDay.set(todayIst);
                circuitTripped.set(false);
                dayLossAbs.set(BigDecimal.ZERO);
                // re-seed baseline if provided
                if (seedStartEquity != null && seedStartEquity.signum() > 0) {
                    dayStartEquity.set(seedStartEquity);
                } else {
                    dayStartEquity.compareAndSet(null, BigDecimal.ZERO);
                }
            }

            // Short-circuit if already tripped
            if (circuitTripped.get()) return Optional.of(true);

            BigDecimal lossAbs = dayLossAbs.get();
            if (lossAbs == null || lossAbs.signum() < 0) lossAbs = BigDecimal.ZERO;

            // Absolute cap check (if configured)
            boolean hitAbs = (dailyDdCapAbs != null && dailyDdCapAbs.signum() > 0)
                    && (lossAbs.compareTo(dailyDdCapAbs) >= 0);

            // Percent cap check (if baseline available & configured)
            boolean hitPct = false;
            if (!hitAbs && dailyDdCapPct != null && dailyDdCapPct.signum() > 0) {
                BigDecimal base = dayStartEquity.get();
                if (base != null && base.signum() > 0) {
                    BigDecimal lossPct = lossAbs.multiply(BigDecimal.valueOf(100))
                            .divide(base, 2, java.math.RoundingMode.HALF_UP);
                    hitPct = lossPct.compareTo(dailyDdCapPct) >= 0;
                }
            }

            boolean tripped = hitAbs || hitPct;
            if (tripped) circuitTripped.set(true);

            return Optional.of(tripped);
        } catch (Exception t) {
            // On any failure, be safe and do not trip implicitly
            return Optional.of(false);
        }
    }

    /**
     * Call this from your PnL updater (Engine/Trades) to keep losses current (pass a positive loss amount).
     */
    public void updateDailyLossAbs(BigDecimal lossAbs) {
        if (lossAbs != null && lossAbs.signum() >= 0) {
            dayLossAbs.set(lossAbs);
        }
    }

    /**
     * Optionally set/refresh the baseline at the start of trading day.
     */
    public void setDayStartEquity(BigDecimal equity) {
        if (equity != null && equity.signum() > 0) {
            dayStartEquity.set(equity);
        }
    }
}
