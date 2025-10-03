package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.constants.BotConsts;
import com.trade.frankenstein.trader.common.constants.RiskConstants;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.upstox.api.GetMarketQuoteOHLCResponseV3;
import com.upstox.api.MarketQuoteOHLCV3;
import com.upstox.api.OhlcV3;
import com.upstox.api.PlaceOrderRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    // ----- Circuit config -----
    private final float dailyDdCapAbs = 0f; // absolute rupee cap (optional)
    private final float seedStartEquity = 0f; // baseline for pct cap (optional)

    // ----- Circuit live state -----
    private final AtomicReference<LocalDate> ddDay = new AtomicReference<>(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    private final AtomicReference<Float> dayStartEquity = new AtomicReference<>(0f);
    private final AtomicReference<Float> dayLossAbs = new AtomicReference<>(0f); // positive number if losing
    private final AtomicBoolean circuitTripped = new AtomicBoolean(false);

    // Local fallback for orders/min when Redis is unavailable
    private final Deque<Instant> orderTimestamps = new ArrayDeque<>();

    @Autowired
    private UpstoxService upstox;
    @Autowired
    private PortfolioService portfolioService;
    @Autowired
    private StreamGateway stream;
    @Autowired
    private FastStateStore fast;
    @Autowired
    private EventPublisher bus;
    @Autowired
    private ObjectMapper mapper;

    // --- Dynamic DD Cap (Enhancement 1) ---
    private volatile float dynamicDailyDdCapPct = 3.0f; // default fallback

    /**
     * Call at session open, after sharp swings, or periodically
     */
    public void refreshDynamicRiskBudget() {
        Result<PortfolioService.PortfolioSummary> result = portfolioService.getPortfolioSummary();
        if (result.isOk()) {
            PortfolioService.PortfolioSummary summary = result.get();
            BigDecimal dayPnlPct = summary.getDayPnlPct();
            BigDecimal totalPnlPct = summary.getTotalPnlPct();
            int positionsCount = summary.getPositionsCount();

            float minCap = 1.0f, maxCap = 5.0f;
            float baseCap = 3.0f;
            if (positionsCount > 10) baseCap += 0.5f;
            if (dayPnlPct.signum() < 0) baseCap -= 0.5f;
            if (totalPnlPct.floatValue() > 10f) baseCap += 0.5f;
            dynamicDailyDdCapPct = Math.max(minCap, Math.min(baseCap, maxCap));
        } else {
            dynamicDailyDdCapPct = 3.0f;
        }
        log.info("Dynamic daily DD cap set to {}%", dynamicDailyDdCapPct);
    }

    /**
     * Circuit guard using dynamic DD cap
     */
    private boolean isDailyCircuitTrippedDynamic(float lossAbs, float startEquity) {
        float ddPct = dynamicDailyDdCapPct;
        float cap = startEquity > 0f ? round2((ddPct * startEquity) / 100f) : RiskConstants.DAILY_LOSS_CAP.floatValue();
        return lossAbs >= cap;
    }

    /**
     * Automatically updates budget every 30 min during market hours
     */
    @Scheduled(cron = "0 */30 9-15 * * MON-FRI")
    public void scheduledRiskBudgetRefresh() {
        refreshDynamicRiskBudget();
    }

    // --- Helpers ---
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

    // --- ORDER CHECK ---
    @Transactional(readOnly = true)
    public Result checkOrder(PlaceOrderRequest req) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in : check Order");
            return Result.fail("user-not-logged-in");
        }
        if (req == null) return Result.fail("BAD_REQUEST", "PlaceOrderRequest is required");

        // 1) Symbol blacklist
        String instrumentKey = req.getInstrumentToken();
        if (instrumentKey != null && RiskConstants.BLACKLIST_SYMBOLS.stream().anyMatch(instrumentKey::contains)) {
            return Result.fail("SYMBOL_BLOCKED", "Blocked instrument: " + instrumentKey);
        }

        // 2) Throttle: orders/minute
        double ordPct = getOrdersPerMinutePct(); // 0..100
        if (ordPct >= 100.0) {
            return Result.fail("THROTTLED", "Orders per minute throttle reached");
        }

        // 3) SL cool-down
        int mins = getMinutesSinceLastSl(instrumentKey);
        if (mins >= 0 && mins < BotConsts.Risk.SL_COOLDOWN_MINUTES) {
            return Result.fail("SL_COOLDOWN", "Wait " + (BotConsts.Risk.SL_COOLDOWN_MINUTES - mins) + "m after last SL");
        }

        // 4) Disable re-entry after 2 SL
        int rsToday = getRestrikesToday(instrumentKey);
        if (rsToday >= 2) {
            return Result.fail("REENTRY_DISABLED", "Max re-entries reached for today");
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

        // 6) Dynamic daily loss guard
        try {
            float lossNow = (float) currentLossRupees();
            float startEquity = nzf(dayStartEquity.get());
            refreshDynamicRiskBudget(); // update before check
            if (isDailyCircuitTrippedDynamic(lossNow, startEquity)) {
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
            JsonNode n = mapper.valueToTree(buildSnapshot());
            stream.publishRisk("summary", n.toPrettyString());
        } catch (Exception ignored) {
        }
        try {
            publishRiskEvent("summary", buildSnapshot(), "order-placed");
        } catch (Throwable ignored) {
        }
    }

    // --- LIVE COMPUTATIONS (no reflection) ---
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

    // --- SUMMARIES & CIRCUIT ---
    @Transactional(readOnly = true)
    public Result getSummary() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in");
            return Result.fail("user-not-logged-in");
        }
        try {
            RiskSnapshot snap = buildSnapshot();
            try {
                JsonNode n = mapper.valueToTree(snap);
                stream.publishRisk("summary", n.toPrettyString());
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

    public void refreshDailyLossFromBroker() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in");
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
            // Auto-trip circuit if needed
            float startEquity = nzf(dayStartEquity.get());
            if (isDailyCircuitTrippedDynamic(lossAbs, startEquity)) {
                try {
                    stream.publishRisk("circuit", Boolean.TRUE.toString());
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
            log.info("User not logged in");
            return Result.fail("user-not-logged-in");
        }
        float lossAbs = nzf(dayLossAbs.get());
        float startEquity = nzf(dayStartEquity.get());
        boolean tripped = isDailyCircuitTrippedDynamic(lossAbs, startEquity);
        try {
            stream.publishRisk("circuit", Boolean.valueOf(tripped).toString());
        } catch (Exception ignored) {
        }
        return Result.ok(tripped);
    }

    private RiskSnapshot buildSnapshot() {
        Instant now = Instant.now();
        float loss = 0.0f;
        try {
            loss = (float) currentLossRupees();
        } catch (Exception ignored) {
        }
        float startEquity = nzf(dayStartEquity.get());
        refreshDynamicRiskBudget();
        float cap = startEquity > 0f ? round2((dynamicDailyDdCapPct * startEquity) / 100f) : RiskConstants.DAILY_LOSS_CAP.floatValue();
        float budgetLeft = Math.max(0.0f, cap - loss);
        double dailyLossPct = (cap > 0.0f) ? Math.min(100.0, (loss / cap) * 100.0) : 0.0;
        double ordersPerMinPct = getOrdersPerMinutePct();
        Integer lotsCap = RiskConstants.MAX_LOTS;
        Integer lotsUsed = null; // derive from live positions when lot sizes are wired
        return RiskSnapshot.builder()
                .asOf(now)
                .riskBudgetLeft((double) budgetLeft)
                .lotsUsed(lotsUsed)
                .lotsCap(lotsCap)
                .dailyLossPct(dailyLossPct)
                .ordersPerMinPct(ordersPerMinPct)
                .build();
    }

    private double currentLossRupees() {
        float realized = 0f;
        try {
            realized = toFloat(upstox.getRealizedPnlToday());
        } catch (Throwable ignored) {
        }
        double pnl = realized; // +ve profit, -ve loss
        return pnl < 0.0 ? -pnl : 0.0;
    }

    /**
     * Call from your PnL updater to keep losses current (pass a positive loss amount).
     */
    public void updateDailyLossAbs(float lossAbs) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in");
            return;
        }
        if (lossAbs >= 0f) {
            dayLossAbs.set(lossAbs);
        }
    }

    public int getMinutesSinceLastSl(String instrumentKey) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in");
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
            log.info("User not logged in");
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
            log.info("User not logged in");
            return false;
        }
        try {
            float lossAbs = (float) currentLossRupees();
            float startEquity = nzf(dayStartEquity.get());
            refreshDynamicRiskBudget();
            float cap = startEquity > 0f ? round2((dynamicDailyDdCapPct * startEquity) / 100f) : RiskConstants.DAILY_LOSS_CAP.floatValue();
            double budgetLeft = Math.max(0.0, cap - lossAbs);
            double budgetPctLeft = (cap > 0.0) ? (budgetLeft * 100.0 / cap) : 100.0;
            double ordPct = getOrdersPerMinutePct(); // 0..100
            boolean throttleOk = ordPct < 100.0;
            return budgetPctLeft >= Math.max(0.0, minBudgetPct) && throttleOk && !isDailyCircuitTrippedDynamic(lossAbs, startEquity);
        } catch (Exception e) {
            return false;
        }
    }

    public void recordStopLoss(String instrumentKey) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info("User not logged in");
            return;
        }
        if (instrumentKey == null || instrumentKey.isEmpty()) return;
        try {
            // Store last SL timestamp (secs) with a 24h TTL
            fast.put("sl:last:" + instrumentKey, String.valueOf(Instant.now().getEpochSecond()), Duration.ofHours(24));
        } catch (Exception ignored) { /* best-effort */ }
        try {
            // Increment today's restrike count with TTL until midnight IST
            LocalDate d = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            String key = "sl:count:" + instrumentKey + ":" + d;
            fast.incr(key, Duration.ofHours(16));
        } catch (Exception ignored) { /* best-effort */ }
    }

    // --- Event publishing ---
    private void publishRiskEvent(String subTopic, RiskSnapshot snap, String reason) {
        try {
            // SSE (UI)
            try {
                JsonNode n = mapper.valueToTree(snap);
                stream.publishRisk(subTopic, n.toPrettyString());
            } catch (Throwable ignored) { /* best-effort SSE */ }
            // Kafka (internal bus)
            try {
                final JsonObject o = new JsonObject();
                o.addProperty("ts", java.time.Instant.now().toEpochMilli());
                o.addProperty("ts_iso", java.time.Instant.now().toString());
                o.addProperty("event", "risk.summary");
                o.addProperty("source", "risk");
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
                stream.publishRisk("circuit", Boolean.valueOf(tripped).toString());
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

    public Optional<Boolean> isDailyCircuitTripped() {
        float lossAbs = nzf(dayLossAbs.get());
        float startEquity = nzf(dayStartEquity.get());
        boolean tripped = isDailyCircuitTrippedDynamic(lossAbs, startEquity);
        return Optional.of(tripped);
    }

    /**
     * Returns the current daily loss percentage (0..100),
     * computed using the dynamic daily drawdown cap.
     */
    public double getDailyLossPct() {
        float lossAbs = nzf(dayLossAbs.get());
        float startEquity = nzf(dayStartEquity.get());
        refreshDynamicRiskBudget(); // Ensure the latest cap is loaded
        float cap = startEquity > 0f
                ? round2((dynamicDailyDdCapPct * startEquity) / 100f)
                : RiskConstants.DAILY_LOSS_CAP.floatValue();

        return (cap > 0.0f) ? Math.min(100.0, (lossAbs / cap) * 100.0) : 0.0;
    }

    /**
     * Returns the current invested amount, exposure value, and position count.
     */
    public PortfolioService.PortfolioSummary getLivePortfolioSummary() {
        Result<PortfolioService.PortfolioSummary> summaryRes = portfolioService.getPortfolioSummary();
        if (summaryRes.isOk()) {
            return summaryRes.get();
        }
        // fallback: empty summary if not available
        return new PortfolioService.PortfolioSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    /**
     * Returns the overall open lots for a specific underlying (e.g., "NIFTY").
     */
    public int getOpenLotsForUnderlying(String underlyingKey) {
        Result<Integer> lotsRes = portfolioService.getOpenLotsForUnderlying(underlyingKey);
        return lotsRes.isOk() ? lotsRes.get() : 0;
    }

    /**
     * Returns the net delta (directional risk) for a specific underlying.
     */
    public BigDecimal getNetDeltaForUnderlying(String underlyingKey) {
        Result<BigDecimal> deltaRes = portfolioService.getNetDeltaForUnderlying(underlyingKey);
        return deltaRes.isOk() ? deltaRes.get() : BigDecimal.ZERO;
    }

    /**
     * Returns risk greeks for a given underlying (delta, gamma, theta, vega).
     */
    public PortfolioService.PortfolioGreeks getNetGreeksForUnderlying(String underlyingKey) {
        Result<PortfolioService.PortfolioGreeks> greeksRes = portfolioService.getNetGreeksForUnderlying(underlyingKey);
        return greeksRes.isOk() ? greeksRes.get() : new PortfolioService.PortfolioGreeks(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

}
