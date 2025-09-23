package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.constants.RiskConstants;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.upstox.api.GetMarketQuoteOHLCResponseV3;
import com.upstox.api.MarketQuoteOHLCV3;
import com.upstox.api.OhlcV3;
import com.upstox.api.PlaceOrderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final FastStateStore fast; // Redis/in-mem per config

    // --- Circuit config (can be externalized later) ---
    private float dailyDdCapPct = 3.0f;   // percent-of-equity cap (optional)
    private float dailyDdCapAbs = 0f;     // absolute rupee cap (optional)
    private float seedStartEquity = 0f;   // baseline for pct cap (optional)

    // --- Circuit live state (IST day roll-over safe) ---
    private final AtomicReference<LocalDate> ddDay = new AtomicReference<>(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    private final AtomicReference<Float> dayStartEquity = new AtomicReference<>(0f);
    private final AtomicReference<Float> dayLossAbs = new AtomicReference<>(0f); // positive number if losing
    private final AtomicBoolean circuitTripped = new AtomicBoolean(false);

    // Local fallback for orders/min when Redis is unavailable
    private final Deque<Instant> orderTimestamps = new ArrayDeque<>();

    // =====================================================================
    // ORDER CHECK
    // =====================================================================
    @Transactional(readOnly = true)
    public Result<Void> checkOrder(PlaceOrderRequest req) {
        if (req == null) return Result.fail("BAD_REQUEST", "PlaceOrderRequest is required");

        // 0) Daily circuit
        if (isDailyCircuitTripped().orElse(false)) {
            return Result.fail("CIRCUIT_TRIPPED", "Daily risk circuit is active");
        }

        // 1) Basic symbol blacklist — match by instrument token (no reflection)
        String instrumentKey = req.getInstrumentToken();
        if (instrumentKey != null && RiskConstants.BLACKLIST_SYMBOLS.stream().anyMatch(instrumentKey::contains)) {
            return Result.fail("SYMBOL_BLOCKED", "Blocked instrument: " + instrumentKey);
        }

        // 2) Throttle: orders/minute (Redis-backed with local fallback)
        double ordPct = getOrdersPerMinutePct();
        if (ordPct >= 100.0) {
            return Result.fail("THROTTLED", "Orders per minute throttle reached");
        }

        // 3) Market hygiene: live 1m bar roughness as a slippage proxy
        try {
            if (instrumentKey != null && !instrumentKey.isEmpty()) {
                double roughnessPct = readLiveBarRoughnessPct(instrumentKey); // ((H-L)/mid)*100
                double maxSlip = RiskConstants.MAX_SLIPPAGE_PCT.doubleValue();
                if (!Double.isNaN(roughnessPct) && roughnessPct > maxSlip) {
                    return Result.fail(
                            "SLIPPAGE_HIGH",
                            String.format(Locale.ROOT, "Live bar roughness %.2f%% exceeds %.2f%%", roughnessPct, maxSlip)
                    );
                }
            }
        } catch (Exception t) {
            log.warn("checkOrder: slippage read failed (allowing order): {}", t.toString());
        }

        // 4) Daily loss cap (realized only, unless you add unrealized in UpstoxService later)
        try {
            double lossNow = currentLossRupees(); // positive if losing
            double cap = RiskConstants.DAILY_LOSS_CAP.doubleValue();
            if (lossNow >= cap - 1e-6) {
                return Result.fail("DAILY_LOSS_BREACH", "Daily loss cap reached");
            }
        } catch (Exception t) {
            log.warn("checkOrder: PnL read failed (allowing order): {}", t.toString());
        }

        return Result.ok(null);
    }

    /**
     * Call AFTER a successful placeOrder to advance the rolling throttle.
     */
    public void noteOrderPlaced() {
        Instant now = Instant.now();
        try {
            fast.incr("orders_per_min", Duration.ofSeconds(60));
        } catch (Exception e) {
            // Fallback: local rolling window (best-effort)
            orderTimestamps.addLast(now);
            evictOlderThan(now.minusSeconds(60));
        }
        try {
            stream.send("risk.summary", buildSnapshot());
        } catch (Exception ignored) {
        }
    }

    // =====================================================================
    // SUMMARIES & CIRCUIT
    // =====================================================================
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
     * Refresh today's realized PnL from broker and update loss (positive if losing).
     * (Unrealized can be added later if your UpstoxService exposes it — we intentionally
     * do NOT fake unrealized by reusing realized.)
     */
    public void refreshDailyLossFromBroker() {
        try {
            float realized = toFloat(upstox.getRealizedPnlToday()); // +ve profit, -ve loss
            float lossAbs = realized < 0f ? -realized : 0f;
            updateDailyLossAbs(lossAbs);
        } catch (Exception t) {
            log.warn("refreshDailyLossFromBroker failed: {}", t.toString());
        }
    }

    public Result<Boolean> getCircuitState() {
        boolean tripped = isDailyCircuitTripped().orElse(false);
        try {
            stream.send("risk.circuit", tripped);
        } catch (Exception ignored) {
        }
        return Result.ok(tripped);
    }

    public Result<Void> tripCircuit(String reason) {
        circuitTripped.set(true);
        try {
            stream.send("risk.circuit", true);
        } catch (Exception ignored) {
        }
        if (reason != null && !reason.isEmpty()) log.warn("Risk circuit TRIPPED: {}", reason);
        return Result.ok(null);
    }

    public Result<Void> resetCircuit() {
        circuitTripped.set(false);
        try {
            stream.send("risk.circuit", false);
        } catch (Exception ignored) {
        }
        log.info("Risk circuit RESET");
        return Result.ok(null);
    }

    // =====================================================================
    // SNAPSHOT
    // =====================================================================
    private RiskSnapshot buildSnapshot() {
        Instant now = Instant.now();

        double cap = RiskConstants.DAILY_LOSS_CAP.doubleValue();
        double loss = 0.0;
        try {
            loss = currentLossRupees();
        } catch (Exception ignored) {
        }

        double budgetLeft = Math.max(0.0, cap - loss);
        double dailyLossPct = (cap > 0.0) ? Math.min(100.0, (loss / cap) * 100.0) : 0.0;
        double ordersPerMinPct = getOrdersPerMinutePct();

        Integer lotsCap = RiskConstants.MAX_LOTS;
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

    // =====================================================================
    // LIVE COMPUTATIONS (no reflection)
    // =====================================================================

    /**
     * Positive rupee loss today (0 if flat/profit).
     */
    private double currentLossRupees() {
        // Realized only for now (unrealized can be integrated when available)
        float realized = 0f;
        try {
            realized = toFloat(upstox.getRealizedPnlToday());
        } catch (Throwable ignored) {
        }
        double pnl = realized; // +ve profit, -ve loss
        return pnl < 0.0 ? -pnl : 0.0;
    }

    /**
     * Orders/minute usage as % of cap; prefers Redis count, falls back to local deque.
     */
    private double getOrdersPerMinutePct() {
        final int cap = Math.max(1, RiskConstants.ORDERS_PER_MINUTE);
        try {
            Optional<String> v = fast.get("orders_per_min");
            if (v.isPresent()) {
                long count = Long.parseLong(v.get());
                return clamp01((count * 100.0) / cap);
            }
        } catch (Exception ignored) { /* fall back */ }
        evictOlderThan(Instant.now().minusSeconds(60));
        int count = orderTimestamps.size();
        return clamp01((count * 100.0) / cap);
    }

    /**
     * Live bar roughness % = ((high - low) / mid) * 100 for the current 1-minute bar.
     * Uses SDK v3 OHLC (open/high/low/close). No LTP here by design.
     */
    private double readLiveBarRoughnessPct(String instrumentKey) {
        GetMarketQuoteOHLCResponseV3 q = upstox.getMarketOHLCQuote(instrumentKey, "I1");
        if (q == null || q.getData() == null) return Double.NaN;
        MarketQuoteOHLCV3 d = q.getData().get(instrumentKey);
        if (d == null || d.getLiveOhlc() == null) return Double.NaN;
        OhlcV3 o = d.getLiveOhlc();
        double high = o.getHigh();
        double low = o.getLow();
        double mid = (high + low) / 2.0;
        if (mid <= 0.0 || high < low) return Double.NaN;
        return ((high - low) / mid) * 100.0;
    }

    // =====================================================================
    // HELPERS (no reflection)
    // =====================================================================
    private static double clamp01(double pct) {
        return Math.max(0.0, Math.min(100.0, pct));
    }

    private void evictOlderThan(Instant threshold) {
        while (true) {
            Instant head = orderTimestamps.peekFirst();
            if (head == null || !head.isBefore(threshold)) break;
            orderTimestamps.pollFirst();
        }
    }

    public Optional<Boolean> isDailyCircuitTripped() {
        try {
            // Reset on new trading day (IST)
            LocalDate todayIst = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            LocalDate stored = ddDay.get();
            if (stored == null || !stored.equals(todayIst)) {
                ddDay.set(todayIst);
                circuitTripped.set(false);
                dayLossAbs.set(0f);
                dayStartEquity.set(seedStartEquity > 0f ? seedStartEquity : 0f);
            }

            if (circuitTripped.get()) return Optional.of(true);

            float lossAbs = nzf(dayLossAbs.get());
            boolean hitAbs = (dailyDdCapAbs > 0f) && (lossAbs >= dailyDdCapAbs);

            boolean hitPct = false;
            if (!hitAbs && dailyDdCapPct > 0f) {
                float base = nzf(dayStartEquity.get());
                if (base > 0f) {
                    float lossPct = round2((lossAbs * 100f) / base);
                    hitPct = lossPct >= dailyDdCapPct;
                }
            }

            boolean tripped = hitAbs || hitPct;
            if (tripped) circuitTripped.set(true);

            return Optional.of(tripped);
        } catch (Exception t) {
            return Optional.of(false);
        }
    }

    private static float nzf(Float v) {
        return v == null ? 0f : v;
    }

    /**
     * Call from your PnL updater to keep losses current (pass a positive loss amount).
     */
    public void updateDailyLossAbs(float lossAbs) {
        if (lossAbs >= 0f) {
            dayLossAbs.set(lossAbs);
        }
    }

    /**
     * Optionally set/refresh the baseline at the start of trading day.
     */
    public void setDayStartEquity(float equity) {
        if (equity > 0f) {
            dayStartEquity.set(equity);
        }
    }

    private static float round2(float v) {
        return Math.round(v * 100f) / 100f;
    }

    private static float toFloat(Object v) {
        if (v == null) return 0f;
        if (v instanceof Number) return ((Number) v).floatValue();
        try {
            return Float.parseFloat(String.valueOf(v));
        } catch (Exception ignored) {
            return 0f;
        }
    }
}
