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
import com.trade.frankenstein.trader.model.documents.RiskEvent;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.repo.documents.RiskEventRepo;
import com.trade.frankenstein.trader.repo.documents.RiskSnapshotRepo;
import com.upstox.api.*;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class RiskService {
    // Circuit config
    private final float dailyDdCapAbs = 0f; // absolute rupee cap (optional)
    private final float seedStartEquity = 0f; // baseline (optional)

    // Circuit live state
    private final AtomicReference<LocalDate> ddDay = new AtomicReference<>(LocalDate.now(ZoneId.of("Asia/Kolkata")));
    private final AtomicReference<Float> dayStartEquity = new AtomicReference<>(0f);
    private final AtomicReference<Float> dayLossAbs = new AtomicReference<>(0f);
    private final AtomicBoolean circuitTripped = new AtomicBoolean(false);
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
    @Autowired
    private RiskEventRepo riskEventRepo;
    @Autowired
    private RiskSnapshotRepo riskSnapshotRepo;

    private volatile float dynamicDailyDdCapPct = 3.0f;

    public void refreshDynamicRiskBudget() {
        Result<PortfolioService.PortfolioSummary> result = portfolioService.getPortfolioSummary();
        if (result.isOk()) {
            PortfolioService.PortfolioSummary summary = result.get();
            BigDecimal dayPnlPct = summary.getDayPnlPct();
            BigDecimal totalPnlPct = summary.getTotalPnlPct();
            int positionsCount = summary.getPositionsCount();
            float minCap = 1.0f, maxCap = 5.0f, baseCap = 3.0f;
            if (positionsCount > 10) baseCap += 0.5f;
            if (dayPnlPct.signum() < 0) baseCap -= 0.5f;
            if (totalPnlPct.floatValue() > 10f) baseCap += 0.5f;
            dynamicDailyDdCapPct = Math.max(minCap, Math.min(baseCap, maxCap));
        } else dynamicDailyDdCapPct = 3.0f;
        log.info("Dynamic daily DD cap set to {}%", dynamicDailyDdCapPct);
    }

    private boolean isDailyCircuitTrippedDynamic(float lossAbs, float startEquity) {
        float ddPct = dynamicDailyDdCapPct;
        float cap = startEquity > 0f ? round2((ddPct * startEquity) / 100f) : RiskConstants.DAILY_LOSS_CAP.floatValue();
        boolean tripped = lossAbs >= cap;
        if (tripped) recordRiskEvent("DAILY_LOSS_BREACH", "Loss breached dynamic cap", null, lossAbs, true);
        return tripped;
    }

    @Scheduled(cron = "0 */30 9-15 * * MON-FRI")
    public void scheduledRiskBudgetRefresh() {
        refreshDynamicRiskBudget();
    }

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

    @Transactional(readOnly = true)
    public Result checkOrder(PlaceOrderRequest req) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return Result.fail("user-not-logged-in");
        if (isKillSwitchOpenNew())
            return Result.fail("KILL_SWITCH_OPEN_NEW", "New position lockout: exposure breach active");
        if (req == null) return Result.fail("BAD_REQUEST", "PlaceOrderRequest is required");
        String instrumentKey = req.getInstrumentToken();
        if (instrumentKey != null && RiskConstants.BLACKLIST_SYMBOLS.stream().anyMatch(instrumentKey::contains))
            return Result.fail("SYMBOL_BLOCKED", "Blocked instrument: " + instrumentKey);

        double ordPct = getOrdersPerMinutePct();
        if (ordPct >= 100.0) return Result.fail("THROTTLED", "Orders per minute throttle reached");
        int mins = getMinutesSinceLastSl(instrumentKey);
        if (mins >= 0 && mins < BotConsts.Risk.SL_COOLDOWN_MINUTES)
            return Result.fail("SL_COOLDOWN", "Wait " + (BotConsts.Risk.SL_COOLDOWN_MINUTES - mins) + "m after last SL");
        int rsToday = getRestrikesToday(instrumentKey);
        if (rsToday >= 2) return Result.fail("REENTRY_DISABLED", "Max re-entries reached for today");
        try {
            if (instrumentKey != null && !instrumentKey.isEmpty()) {
                double roughnessPct = readLiveBarRoughnessPct(instrumentKey);
                double maxSlip = RiskConstants.MAX_SLIPPAGE_PCT.doubleValue();
                if (!Double.isNaN(roughnessPct) && roughnessPct > maxSlip)
                    return Result.fail("SLIPPAGE_HIGH",
                            String.format(Locale.ROOT, "Live bar roughness %.2f%% exceeds %.2f%%", roughnessPct, maxSlip));
            }
        } catch (Exception t) {
            log.warn("checkOrder: slippage read failed (allowing order): {}", t.toString());
        }

        try {
            float lossNow = (float) currentLossRupees();
            float startEquity = nzf(dayStartEquity.get());
            refreshDynamicRiskBudget();
            if (isDailyCircuitTrippedDynamic(lossNow, startEquity))
                return Result.fail("DAILY_LOSS_BREACH", "Daily loss cap reached");
        } catch (Exception t) {
            log.warn("checkOrder: PnL read failed (allowing order): {}", t.toString());
        }
        String underlying = extractUnderlyingFromInstrument(req.getInstrumentToken());
        int maxLots = 30; // ideally from config
        BigDecimal maxDelta = new BigDecimal("10000");
        boolean headroom = hasExposureHeadroom(underlying, maxLots, maxDelta);
        if (!headroom) {
            recordRiskEvent("LOTS_CAP", "Max lots exceeded in " + underlying, null, getOpenLotsForUnderlying(underlying), true);
            return Result.fail("EXPOSURE_LIMIT", "Open lots or net delta limit reached for " + underlying);
        }
        return Result.ok(null);
    }

    private String extractUnderlyingFromInstrument(String instrumentTokenOrSymbol) {
        if (instrumentTokenOrSymbol == null) return "";
        String tok = instrumentTokenOrSymbol.toUpperCase();
        if (tok.contains("BANKNIFTY")) return "BANKNIFTY";
        if (tok.contains("NIFTY")) return "NIFTY";
        if (tok.contains("FINNIFTY")) return "FINNIFTY";
        return "NIFTY";
    }

    public boolean hasExposureHeadroom(String underlyingKey, int maxLots, BigDecimal maxDelta) {
        int lotsOpen = getOpenLotsForUnderlying(underlyingKey);
        BigDecimal netDelta = getNetDeltaForUnderlying(underlyingKey);
        boolean ok = lotsOpen <= maxLots && netDelta.abs().compareTo(maxDelta) <= 0;
        if (!ok) recordRiskEvent("LOTS_CAP", "Lots/netdelta exposure breached: " + underlyingKey, null, lotsOpen, true);
        return ok;
    }

    public void noteOrderPlaced() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return;
        Instant now = Instant.now();
        try {
            fast.incr("orders_per_min", Duration.ofSeconds(60));
        } catch (Exception e) {
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

    private double getOrdersPerMinutePct() {
        final int cap = Math.max(1, RiskConstants.ORDERS_PER_MINUTE);
        try {
            Optional<String> v = fast.get("orders_per_min");
            if (v.isPresent()) {
                long count = Long.parseLong(v.get());
                return clamp01((count * 100.0) / cap);
            }
        } catch (Exception ignored) {
        }
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
        double high = o.getHigh(), low = o.getLow(), mid = (high + low) / 2.0;
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

    @Transactional(readOnly = true)
    public Result getSummary() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return Result.fail("user-not-logged-in");
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return;
        try {
            float realized = toFloat(upstox.getRealizedPnlToday());
            float lossAbs = realized < 0f ? -realized : 0f;
            updateDailyLossAbs(lossAbs);
            try {
                publishRiskEvent("summary", buildSnapshot(), "pnl-refresh");
            } catch (Throwable ignored) {
            }
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return Result.fail("user-not-logged-in");
        float lossAbs = nzf(dayLossAbs.get()), startEquity = nzf(dayStartEquity.get());
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
        Integer lotsUsed = null; // TODO: derive from live positions
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
        double pnl = realized;
        return pnl < 0.0 ? -pnl : 0.0;
    }

    public void updateDailyLossAbs(float lossAbs) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return;
        if (lossAbs >= 0f) dayLossAbs.set(lossAbs);
    }

    public int getMinutesSinceLastSl(String instrumentKey) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return -1;
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return 0;
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
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return false;
        try {
            float lossAbs = (float) currentLossRupees();
            float startEquity = nzf(dayStartEquity.get());
            refreshDynamicRiskBudget();
            float cap = startEquity > 0f ? round2((dynamicDailyDdCapPct * startEquity) / 100f) : RiskConstants.DAILY_LOSS_CAP.floatValue();
            double budgetLeft = Math.max(0.0, cap - lossAbs);
            double budgetPctLeft = (cap > 0.0) ? (budgetLeft * 100.0 / cap) : 100.0;
            double ordPct = getOrdersPerMinutePct();
            boolean throttleOk = ordPct < 100.0;
            return budgetPctLeft >= Math.max(0.0, minBudgetPct) && throttleOk && !isDailyCircuitTrippedDynamic(lossAbs, startEquity);
        } catch (Exception e) {
            return false;
        }
    }

    public void recordStopLoss(String instrumentKey) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return;
        if (instrumentKey == null || instrumentKey.isEmpty()) return;
        try {
            fast.put("sl:last:" + instrumentKey, String.valueOf(Instant.now().getEpochSecond()), Duration.ofHours(24));
        } catch (Exception ignored) {
        }
        try {
            LocalDate d = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            String key = "sl:count:" + instrumentKey + ":" + d;
            fast.incr(key, Duration.ofHours(16));
        } catch (Exception ignored) {
        }
    }

    private void publishRiskEvent(String subTopic, RiskSnapshot snap, String reason) {
        try {
            try {
                JsonNode n = mapper.valueToTree(snap);
                stream.publishRisk(subTopic, n.toPrettyString());
            } catch (Throwable ignored) {
            }
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
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignoredOuter) {
        }
    }

    private void publishCircuitState(boolean tripped, String reason) {
        try {
            try {
                stream.publishRisk("circuit", Boolean.valueOf(tripped).toString());
            } catch (Throwable ignored) {
            }
            try {
                final JsonObject o = new JsonObject();
                o.addProperty("ts", java.time.Instant.now().toEpochMilli());
                if (reason != null && reason.length() > 0) o.addProperty("reason", reason);
                o.addProperty("circuit", tripped);
                String key = "circuit";
                bus.publish(EventBusConfig.TOPIC_RISK, key, o.toString());
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignoredOuter) {
        }
    }

    public Optional<Boolean> isDailyCircuitTripped() {
        float lossAbs = nzf(dayLossAbs.get()), startEquity = nzf(dayStartEquity.get());
        boolean tripped = isDailyCircuitTrippedDynamic(lossAbs, startEquity);
        return Optional.of(tripped);
    }

    public double getDailyLossPct() {
        float lossAbs = nzf(dayLossAbs.get()), startEquity = nzf(dayStartEquity.get());
        refreshDynamicRiskBudget();
        float cap = startEquity > 0f ? round2((dynamicDailyDdCapPct * startEquity) / 100f) : RiskConstants.DAILY_LOSS_CAP.floatValue();
        return (cap > 0.0f) ? Math.min(100.0, (lossAbs / cap) * 100.0) : 0.0;
    }

    public PortfolioService.PortfolioSummary getLivePortfolioSummary() {
        Result<PortfolioService.PortfolioSummary> summaryRes = portfolioService.getPortfolioSummary();
        if (summaryRes.isOk()) return summaryRes.get();
        return new PortfolioService.PortfolioSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    public int getOpenLotsForUnderlying(String underlyingKey) {
        Result<Integer> lotsRes = portfolioService.getOpenLotsForUnderlying(underlyingKey);
        return lotsRes.isOk() ? lotsRes.get() : 0;
    }

    public BigDecimal getNetDeltaForUnderlying(String underlyingKey) {
        Result<BigDecimal> deltaRes = portfolioService.getNetDeltaForUnderlying(underlyingKey);
        return deltaRes.isOk() ? deltaRes.get() : BigDecimal.ZERO;
    }

    public PortfolioService.PortfolioGreeks getNetGreeksForUnderlying(String underlyingKey) {
        Result<PortfolioService.PortfolioGreeks> greeksRes = portfolioService.getNetGreeksForUnderlying(underlyingKey);
        return greeksRes.isOk() ? greeksRes.get() : new PortfolioService.PortfolioGreeks(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Scheduled(cron = "0 */5 9-15 * * MON-FRI")
    public void scheduledExposureSweep() {
        String[] keys = {"NIFTY", "BANKNIFTY", "FINNIFTY"};
        int maxLots = 30;
        BigDecimal maxDelta = new BigDecimal("10000");
        for (String key : keys) {
            boolean ok = hasExposureHeadroom(key, maxLots, maxDelta);
            int lotsOpen = getOpenLotsForUnderlying(key);
            BigDecimal netDelta = getNetDeltaForUnderlying(key);
            if (!ok) {
                publishCircuitState(true, "exposure-limit-exceeded:" + key);
                setKillSwitchOpenNew(true, "Exposure breach: " + key);
                log.warn("KILL SWITCH TRIGGERED: {} lots={}, netDelta={}", key, lotsOpen, netDelta);
                int lotsToReduce = Math.max(0, lotsOpen - maxLots);
                if (lotsToReduce > 0) autoHedgeOrUnwind(key, lotsToReduce);
            } else {
                setKillSwitchOpenNew(false, "Exposure normalized: " + key);
            }
        }
    }

    private volatile boolean killSwitchOpenNew = false;

    public void setKillSwitchOpenNew(boolean value, String reason) {
        killSwitchOpenNew = value;
        log.info("Kill Switch (open new positions) set to {} due to {}", value, reason);
        publishCircuitState(value, reason);
    }

    public boolean isKillSwitchOpenNew() {
        return killSwitchOpenNew;
    }

    public void autoHedgeOrUnwind(String underlyingKey, int lotsToReduce) {
        if (lotsToReduce <= 0) return;
        try {
            log.warn("AUTO-UNWIND triggered for {}: will try to reduce {} lots", underlyingKey, lotsToReduce);
            Result<GetPositionResponse> pfRes = portfolioService.getPortfolio();
            if (!pfRes.isOk() || pfRes.get() == null) {
                log.error("Unable to get portfolio for auto-unwind: {}", pfRes.getError());
                return;
            }
            List<com.upstox.api.PositionData> rows = pfRes.get().getData();
            if (rows == null || rows.isEmpty()) {
                log.info("No open positions in portfolio for {}", underlyingKey);
                return;
            }
            int lotsLeft = lotsToReduce;
            for (com.upstox.api.PositionData row : rows) {
                if (lotsLeft <= 0) break;
                if (row == null) continue;
                String ts = row.getTradingSymbol();
                if (ts == null || !ts.toUpperCase().contains(underlyingKey.toUpperCase())) continue;
                int qty = row.getQuantity() == null ? 0 : row.getQuantity();
                if (qty == 0) continue;
                int lotSize = portfolioService.defaultLotSizeForSymbol(ts.toUpperCase());
                int absLots = Math.abs(qty) / lotSize;
                int exitLots = Math.min(absLots, lotsLeft);
                try {
                    PlaceOrderRequest req = new PlaceOrderRequest();
                    req.setInstrumentToken(row.getInstrumentToken());
                    req.setQuantity(exitLots * lotSize);
                    req.setTransactionType(qty > 0 ?
                            PlaceOrderRequest.TransactionTypeEnum.SELL :
                            PlaceOrderRequest.TransactionTypeEnum.BUY);
                    req.setOrderType(PlaceOrderRequest.OrderTypeEnum.MARKET);
                    req.setProduct(row.getProduct() != null ? PlaceOrderRequest.ProductEnum.valueOf(row.getProduct()) : PlaceOrderRequest.ProductEnum.I);
                    req.setValidity(PlaceOrderRequest.ValidityEnum.DAY);
                    req.setTag("AUTO-UNWIND");
                    log.warn("Auto-unwind placing {} order for {} ({}) qty={}",
                            req.getTransactionType(), ts, row.getInstrumentToken(), req.getQuantity());
                    upstox.placeOrder(req);
                    lotsLeft -= exitLots;
                } catch (Exception e) {
                    log.error("Auto-unwind order failed for {}: {}", ts, e.getMessage());
                }
            }
            if (lotsLeft > 0) {
                String segment = "FO";
                log.warn("Lots to unwind remain, issuing exitAllPositions for segment {}", segment);
                upstox.exitAllPositions(segment, "AUTO-UNWIND:" + underlyingKey);
            }
            log.warn("Auto-unwind for {} complete. Target: {} lots, remaining: {}", underlyingKey, lotsToReduce, lotsLeft);
            stream.publishRisk("auto-hedge", "Auto-unwind triggered for " + underlyingKey + " reduced " + (lotsToReduce - lotsLeft) + " lots.");
            recordRiskEvent("AUTO_UNWIND", "Auto-unwind for exposure breach: " + underlyingKey, null, lotsToReduce, true);
        } catch (Exception ex) {
            log.error("AUTO-UNWIND fatal error for {}: {}", underlyingKey, ex.getMessage());
            try {
                stream.publishRisk("auto-hedge", "Auto-unwind failed: " + ex.getMessage());
            } catch (Exception ignore) {
            }
            recordRiskEvent("AUTO_UNWIND_FAILED", "Auto-unwind failed: " + ex.getMessage(), null, lotsToReduce, true);
        }
    }

    // ==== RISK EVENT/AUDIT LOGGING ====
    public void recordRiskEvent(String type, String reason, String orderRef, double value, boolean breached) {
        RiskEvent event = RiskEvent.builder()
                .ts(Instant.now())
                .type(type)
                .reason(reason)
                .orderRef(orderRef)
                .value(value)
                .breached(breached)
                .build();
        riskEventRepo.save(event);
        log.info("RiskEvent recorded: {}", event);
    }

    public List<RiskEvent> getRecentRiskEvents(int lastN) {
        return riskEventRepo.findTopNByOrderByTsDesc(lastN);
    }

    public List<RiskEvent> getEventsBetween(Instant start, Instant end) {
        return riskEventRepo.findByTsBetween(start, end);
    }

    public List<RiskSnapshot> getRiskSnapshotsBetween(Instant from, Instant to) {
        return riskSnapshotRepo.findByAsOfBetween(from, to);
    }

    public List<RiskSnapshot> getRecentRiskSnapshots(int lastN) {
        return riskSnapshotRepo.findTopNByOrderByAsOfDesc(lastN);
    }
}
