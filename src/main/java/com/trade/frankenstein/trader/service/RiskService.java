package com.trade.frankenstein.trader.service;

import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.constants.BotConsts;
import com.trade.frankenstein.trader.common.constants.RiskConstants;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.enums.FlagName;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.upstox.api.GetMarketQuoteOHLCResponseV3;
import com.upstox.api.MarketQuoteOHLCV3;
import com.upstox.api.OhlcV3;
import com.upstox.api.PlaceOrderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@Slf4j
public class RiskService {

    // --- Circuit config (can be externalized later) ---
    private final float dailyDdCapPct = 3.0f;   // percent-of-equity cap (optional)
    private final float dailyDdCapAbs = 0f;     // absolute rupee cap (optional)
    private final float seedStartEquity = 0f;   // baseline for pct cap (optional)

    // --- Circuit live state (IST day roll-over safe) ---
    private final AtomicReference<LocalDate> ddDay = new AtomicReference<>(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    private final AtomicReference<Float> dayStartEquity = new AtomicReference<>(0f);
    private final AtomicReference<Float> dayLossAbs = new AtomicReference<>(0f); // positive number if losing
    private final AtomicBoolean circuitTripped = new AtomicBoolean(false);
    // Local fallback for orders/min when Redis is unavailable
    private final Deque<Instant> orderTimestamps = new ArrayDeque<>();

    @Autowired
    private UpstoxService upstox;
    @Autowired
    private StreamGateway stream;
    @Autowired
    private FastStateStore fast;
    @Autowired
    private FlagsService flags;
    @Autowired
    private EventPublisher bus;

    // =====================================================================
    // HELPERS (no reflection)
    // =====================================================================
    private static double clamp01(double pct) {
        return Math.max(0.0, Math.min(100.0, pct));
    }

    private static float nzf(Float v) {
        return v == null ? 0f : v;
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

    // =====================================================================
    // ORDER CHECK
    // =====================================================================
    @Transactional(readOnly = true)
    public Result<Void> checkOrder(PlaceOrderRequest req) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in : check Order");
            return Result.fail("user-not-logged-in");
        }
        if (req == null) return Result.fail("BAD_REQUEST", "PlaceOrderRequest is required");

        // -1) Global kill switch — block new orders if circuit/kill is active
        if (flags.isOn(FlagName.KILL_SWITCH_OPEN_NEW) || flags.isOn(FlagName.CIRCUIT_BREAKER_LOCKOUT)) {
            return Result.fail("CIRCUIT_TRIPPED", "Daily risk circuit is active");
        }


        // 0) Daily circuit — apply only if daily loss guard is enabled
        if (flags.isOn(FlagName.DAILY_LOSS_GUARD) && isDailyCircuitTripped().orElse(false)) {
            return Result.fail("CIRCUIT_TRIPPED", "Daily risk circuit is active");
        }


        // 1) Basic symbol blacklist — match by instrument token
        String instrumentKey = req.getInstrumentToken();
        if (instrumentKey != null && RiskConstants.BLACKLIST_SYMBOLS.stream().anyMatch(instrumentKey::contains)) {
            return Result.fail("SYMBOL_BLOCKED", "Blocked instrument: " + instrumentKey);
        }

        // 2) Throttle: orders/minute — apply only if flag enabled
        if (flags.isOn(FlagName.MAX_ORDERS_PER_MIN_GUARD)) {
            double ordPct = getOrdersPerMinutePct(); // 0..100
            if (ordPct >= 100.0) {
                return Result.fail("THROTTLED", "Orders per minute throttle reached");
            }
        }

        // 3) SL cool-down — apply only if flag enabled
        if (flags.isOn(FlagName.SL_COOLDOWN_ENABLED) && instrumentKey != null) {
            int mins = getMinutesSinceLastSl(instrumentKey);
            if (mins >= 0 && mins < BotConsts.Risk.SL_COOLDOWN_MINUTES) {
                return Result.fail("SL_COOLDOWN", "Wait " + (BotConsts.Risk.SL_COOLDOWN_MINUTES - mins) + "m after last SL");
            }
        }

        // 4) Disable re-entry after 2 SL — apply only if flag enabled
        if (flags.isOn(FlagName.DISABLE_REENTRY_AFTER_2_SL) && instrumentKey != null) {
            int rsToday = getRestrikesToday(instrumentKey);
            if (rsToday >= 2) {
                return Result.fail("REENTRY_DISABLED", "Max re-entries reached for today");
            }
        }

        // 5) Market hygiene: live 1m bar roughness as slippage proxy
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

        // 6) Daily loss guard — apply only if flag enabled
        if (flags.isOn(FlagName.DAILY_LOSS_GUARD)) {
            try {
                double lossNow = currentLossRupees(); // positive if losing
                double cap = RiskConstants.DAILY_LOSS_CAP.doubleValue();
                if (cap > 0.0 && lossNow >= cap - 1e-6) {
                    return Result.fail("DAILY_LOSS_BREACH", "Daily loss cap reached");
                }
            } catch (Exception t) {
                log.warn("checkOrder: PnL read failed (allowing order): {}", t.toString());
            }
        }

        return Result.ok(null);
    }

    /**
     * Call AFTER a successful placeOrder to advance the rolling throttle.
     */
    public void noteOrderPlaced() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in");
            return;
        }
        Instant now = Instant.now();
        try {
            fast.incr("orders_per_min", Duration.ofSeconds(60));
        } catch (Exception e) {
            // Fallback: local rolling window (best-effort)
            orderTimestamps.addLast(now);
            evictOlderThan(now.minusSeconds(60));
        }
        try {
            stream.publishRisk("summary", buildSnapshot());
        } catch (Exception ignored) {
        }
        try {
            publishRiskEvent("summary", buildSnapshot(), "order-placed");
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // LIVE COMPUTATIONS (no reflection)
    // =====================================================================

    // =====================================================================
    // SUMMARIES & CIRCUIT
    // =====================================================================
    @Transactional(readOnly = true)
    public Result<RiskSnapshot> getSummary() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in");
            return Result.fail("user-not-logged-in");
        }
        try {
            RiskSnapshot snap = buildSnapshot();
            try {
                stream.publishRisk("summary", snap);
            } catch (Exception ignored) {
                log.error("stream.send failed", ignored);
            }
            publishRiskEvent("summary", snap, "get-summary");
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        try {
            float realized = toFloat(upstox.getRealizedPnlToday()); // +ve profit, -ve loss
            float lossAbs = realized < 0f ? -realized : 0f;
            updateDailyLossAbs(lossAbs);
            try {
                publishRiskEvent("summary", buildSnapshot(), "pnl-refresh");
            } catch (Throwable ignored) {
            }
            // Auto-trip circuit if needed (drives KILL_SWITCH_OPEN_NEW)
            if (isDailyCircuitTripped().orElse(false)) {
                try {
                    stream.publishRisk("circuit", true);
                } catch (Exception ignored) {
                }
                publishCircuitState(true, "pnl-refresh");
            }
        } catch (Exception t) {
            log.warn("refreshDailyLossFromBroker failed: {}", t.toString());
        }
    }

    public Result<Boolean> getCircuitState() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        boolean tripped = isDailyCircuitTripped().orElse(false);
        try {
            stream.publishRisk("circuit", tripped);
        } catch (Exception ignored) {
        }
        return Result.ok(tripped);
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

    private void evictOlderThan(Instant threshold) {
        while (true) {
            Instant head = orderTimestamps.peekFirst();
            if (head == null || !head.isBefore(threshold)) break;
            orderTimestamps.pollFirst();
        }
    }

    public Optional<Boolean> isDailyCircuitTripped() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Optional.of(false);
        }
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
            if (tripped) {
                boolean prev = circuitTripped.get();
                circuitTripped.set(true);
                if (!prev) publishCircuitState(true, "auto-trip");
            }

            return Optional.of(tripped);
        } catch (Exception t) {
            return Optional.of(false);
        }
    }

    /**
     * Call from your PnL updater to keep losses current (pass a positive loss amount).
     */
    public void updateDailyLossAbs(float lossAbs) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        if (lossAbs >= 0f) {
            dayLossAbs.set(lossAbs);
        }
    }

    public int getMinutesSinceLastSl(String instrumentKey) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return -1;
        }
        if (instrumentKey == null || instrumentKey.isEmpty()) return -1;
        try {
            Optional<String> v = fast.get("sl:last:" + instrumentKey);
            if (!v.isPresent()) return -1;
            long epochSec = Long.parseLong(v.get());
            Instant ts = Instant.ofEpochSecond(epochSec);
            long mins = Duration.between(ts, Instant.now()).toMinutes();
            return (int) Math.max(mins, 0);
        } catch (Exception e) {
            return -1;
        }
    }

    public int getRestrikesToday(String instrumentKey) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return 0;
        }
        if (instrumentKey == null || instrumentKey.isEmpty()) return 0;
        try {
            LocalDate d = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            String key = "sl:count:" + instrumentKey + ":" + d;
            Optional<String> v = fast.get(key);
            return v.isPresent() ? Integer.parseInt(v.get()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean hasHeadroom(double minBudgetPct) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return false;
        }
        try {
            double cap = RiskConstants.DAILY_LOSS_CAP.doubleValue();
            double loss = currentLossRupees();
            double budgetLeft = Math.max(0.0, cap - loss);
            double budgetPctLeft = (cap > 0.0) ? (budgetLeft * 100.0 / cap) : 100.0;

            double ordPct = getOrdersPerMinutePct(); // 0..100
            boolean throttleOk = !flags.isOn(FlagName.MAX_ORDERS_PER_MIN_GUARD) || ordPct < 100.0;

            return budgetPctLeft >= Math.max(0.0, minBudgetPct) && throttleOk && !isDailyCircuitTripped().orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    public void recordStopLoss(String instrumentKey) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        if (instrumentKey == null || instrumentKey.isEmpty()) return;

        try {
            // Store last SL timestamp (secs) with a 24h TTL
            fast.put("sl:last:" + instrumentKey, String.valueOf(Instant.now().getEpochSecond()), Duration.ofHours(24));
        } catch (Exception ignored) { /* best-effort */ }

        try {
            // Increment today's re-strike count with TTL until midnight IST
            LocalDate d = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            String key = "sl:count:" + instrumentKey + ":" + d;
            // Approx: 16 hours TTL covers trading day; adjust if you have midnight computation
            fast.incr(key, Duration.ofHours(16));
        } catch (Exception ignored) { /* best-effort */ }
    }


    // =====================================================================
    // Step-10: Kafka + SSE publishing helpers (no reflection; Java 8)
    // =====================================================================
    private void publishRiskEvent(String subTopic, RiskSnapshot snap, String reason) {
        try {
            // SSE (UI)
            try {
                stream.publishRisk(subTopic, snap);
            } catch (Throwable ignored) { /* best-effort SSE */ }

            // Kafka (internal bus)
            try {
                final JsonObject o = new JsonObject();
                o.addProperty("ts", java.time.Instant.now().toEpochMilli());
                if (reason != null && reason.length() > 0) o.addProperty("reason", reason);
                o.addProperty("subTopic", subTopic == null ? "summary" : subTopic);
                if (snap != null) {
                    o.addProperty("riskBudgetLeft", snap.getRiskBudgetLeft() == null ? 0.0 : snap.getRiskBudgetLeft());
                    o.addProperty("dailyLossPct", snap.getDailyLossPct() == null ? 0.0 : snap.getDailyLossPct());
                    o.addProperty("ordersPerMinPct", snap.getOrdersPerMinPct() == null ? 0.0 : snap.getOrdersPerMinPct());
                    if (snap.getLotsCap() != null) o.addProperty("lotsCap", snap.getLotsCap());
                    if (snap.getLotsUsed() != null) o.addProperty("lotsUsed", snap.getLotsUsed());
                }
                String key = "summary";
                bus.publish(EventBusConfig.TOPIC_RISK, key, o.toString());
            } catch (Throwable ignored) { /* best-effort Kafka */ }
        } catch (Throwable ignoredOuter) { /* swallow */ }
    }

    private void publishCircuitState(boolean tripped, String reason) {
        try {
            // SSE (UI)
            try {
                stream.publishRisk("circuit", tripped);
            } catch (Throwable ignored) { /* best-effort SSE */ }

            // Kafka (internal bus)
            try {
                final JsonObject o = new JsonObject();
                o.addProperty("ts", java.time.Instant.now().toEpochMilli());
                if (reason != null && reason.length() > 0) o.addProperty("reason", reason);
                o.addProperty("circuit", tripped);
                String key = "circuit";
                bus.publish(EventBusConfig.TOPIC_RISK, key, o.toString());
            } catch (Throwable ignored) { /* best-effort Kafka */ }
        } catch (Throwable ignoredOuter) { /* swallow */ }
    }

}