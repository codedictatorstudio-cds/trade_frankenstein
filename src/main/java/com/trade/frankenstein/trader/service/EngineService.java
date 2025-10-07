package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.model.documents.DecisionQuality;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.repo.documents.AdviceRepo;
import com.trade.frankenstein.trader.service.decision.DecisionService;
import com.trade.frankenstein.trader.service.sentiment.SentimentService;
import com.upstox.api.GetIntraDayCandleResponse;
import com.upstox.api.GetMarketQuoteLastTradedPriceResponseV3;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("ALL")
@Service
@Slf4j
public class EngineService {

    private static final Pattern EXIT_HINTS = Pattern.compile("EXIT:\\s*SL=([^,]+),\\s*TP=([^,]+),\\s*TTL=(\\d+)m", Pattern.CASE_INSENSITIVE);
    // --- Re-strike knobs (safe defaults) ---
    private static final int RESTRIKE_CHECK_MINUTES = 5;
    private static final int RESTRIKE_TRIGGER_ATM_SHIFT = 1; // strike steps (50-pt)
    private static final int RESTRIKE_MAX_PER_HOUR = 2;
    private static final int DO_NOT_RESTRIKE_AFTER_HHMM = 1500; // 15:00 IST
    private final Map<String, ExitPlan> exitPlans = new ConcurrentHashMap<>(); // key = instrument_key
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong ticks = new AtomicLong(0);

    @Autowired
    private StreamGateway stream;
    @Autowired
    private SentimentService sentimentService;
    @Autowired
    private DecisionService decisionService;
    @Autowired
    private AdviceService adviceService;
    @Autowired
    private AdviceRepo adviceRepo;
    @Autowired
    private RiskService riskService;
    @Autowired
    private MarketDataService marketDataService;
    @Autowired
    private StrategyService strategyService;
    @Autowired
    private OrdersService ordersService;
    @Autowired
    private UpstoxService upstoxService;
    @Autowired
    private PortfolioService portfolioService;
    @Autowired
    private OptionChainService optionChainService;
    @Autowired
    private EventPublisher bus;
    @Autowired
    private ObjectMapper mapper;

    private int maxExecPerTick = 3;
    private int scanLimit = 50;
    /**
     * Default underlying used by analysis services (keep in sync with others).
     */
    private String underlyingKey = Underlyings.NIFTY;
    /**
     * Analysis tick cadence (ms). Keep modest; services pull live data already.
     */
    private long analysisMs = 15000;
    private volatile Instant startedAt = null;
    private volatile Instant lastTickAt = null;
    private volatile long lastExecuted = 0;
    private volatile String lastError = null;
    // audit state
    private volatile boolean lastCircuitTripped = false;
    private volatile Boolean lastEntriesBlocked = null;
    // Re-strike state
    private volatile Instant lastRestrikeCheckAt = Instant.EPOCH;
    private volatile int restrikeCountThisHour = 0;
    private volatile int restrikeHourKey = -1;

    // Re-strike memory for change-detection
    private volatile Integer lastDirScoreForRestrike = null;
    private volatile AtrBand lastAtrBandForRestrike = null;

    private static void appendReason(StringBuilder sb, String part) {
        if (sb.length() > 0) sb.append("; ");
        sb.append(part);
    }

    private static int computeAtmStrikeInt(float spot, int step) {
        if (step <= 0) step = 50;
        int rounded = Math.round(spot);
        int rem = rounded % step;
        int down = rounded - rem;
        int up = down + step;
        // closest multiple; if tie, bias to down
        return (rounded - down) <= (up - rounded) ? down : up;
    }

    // ---------------- Lifecycle ----------------
    public Result<String> startEngine() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        if (running.get()) {
            log.warn("Engine start requested but already running");
            return Result.ok("engine:already-running");
        }
        running.set(true);
        if (startedAt == null) startedAt = Instant.now();
        log.info("Engine STARTED at {} (maxExecPerTick={}, scanLimit={})",
                startedAt, maxExecPerTick, scanLimit);
        publishState();
        auditLifecycleStarted();
        return Result.ok("engine:started");
    }

    public Result<String> stopEngine() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        if (!running.get()) {
            log.warn("Engine stop requested but already stopped");
            return Result.ok("engine:already-stopped");
        }
        running.set(false);
        log.info("Engine STOPPED");
        publishState();
        auditLifecycleStopped();
        return Result.ok("engine:stopped");
    }

    public Result<EngineState> getEngineState() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return Result.fail("user-not-logged-in");
        }
        EngineState state = new EngineState(
                running.get(), startedAt, lastTickAt, ticks.get(), lastExecuted, lastError, Instant.now()
        );
        return Result.ok(state);
    }

    // ---------------- Tick Loop ----------------
    @Scheduled(fixedDelayString = "${trade.engine.tick-ms:2000}")
    public void tick() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        if (!running.get()) {
            log.debug("tick(): engine not running, skip");
            return;
        }

        lastTickAt = Instant.now();
        lastExecuted = 0;
        lastError = null;

        // -------- Evaluate flags (auto policy) --------
        RiskSnapshot rs = null;
        try {
            Result<RiskSnapshot> res = riskService.getSummary();
            if (res != null && res.isOk()) rs = res.get();
        } catch (Exception ignore) {
        }


        if (rs != null && rs.getOrdersPerMinPct() != null) {
            double load = rs.getOrdersPerMinPct() / 100.0;
            if (load < 0.0) load = 0.0;
            if (load > 1.0) load = 1.0;
        }

        // Headroom: budget left, lots cap, throttle, daily loss
        boolean headroom = true;
        if (rs != null) {
            Double budgetLeft = rs.getRiskBudgetLeft();
            Integer used = rs.getLotsUsed();
            Integer cap = rs.getLotsCap();
            Double ordPct = rs.getOrdersPerMinPct();
            headroom = (budgetLeft == null || budgetLeft > 0.0)
                    && (cap == null || used == null || used < cap)
                    && (ordPct == null || ordPct < 100.0);
        }
        try {
            if (lastCircuitTripped != isCircuitLikeTripped(rs)) {
                auditCircuitChange(isCircuitLikeTripped(rs), rs);
                lastCircuitTripped = isCircuitLikeTripped(rs);
            }
        } catch (Throwable ignore) {
        }
        // ---- Push EngineInputs to Strategy/Decision (SSE) ----
        try {
            EngineInputs engIn = new EngineInputs();
            engIn.setRiskHeadroomOk(headroom);
            engIn.setMinutesSinceLastSl(rs.getMinutesSinceLastSl());
            engIn.setRestrikesToday(rs.getRestrikesToday());
            Double opm = (rs != null && rs.getOrdersPerMinPct() != null) ? rs.getOrdersPerMinPct() : null;
            engIn.setOrdersPerMinPct(opm);
            try {
                stream.publish("strategy.inputs", "strategy", engIn);
            } catch (Exception ignore) {
            }
            try {
                JsonNode node = mapper.valueToTree(engIn);
                stream.publishDecision("decision.inputs", node.toPrettyString());
            } catch (Exception ignore) {
            }
        } catch (Exception ignore) {
        }


        // Signals (safe fallbacks)
        Float atrPctNow = getCurrentAtrPctSafe();
        Optional<Float> jumpOpt = marketDataService.getAtrJump5mPct(underlyingKey);
        Optional<Float> rangeOpt = marketDataService.getIntradayRangePct(underlyingKey, "minutes", "5");

        // SL/Restrikes
        try {
            int mins = riskService.getMinutesSinceLastSl(Underlyings.NIFTY);
            int rsToday = riskService.getRestrikesToday(Underlyings.NIFTY);
        } catch (Exception ignored) {
        }

        // -------- Keep risk PnL in sync --------
        try {
            riskService.refreshDailyLossFromBroker();
        } catch (Exception ex) {
            log.info("tick(): risk broker-PnL refresh failed: {}", ex.getMessage(), ex);
        }

        // -------- Generate new advices (only if allowed) --------
        final boolean riskBlock = !headroom || isCircuitLikeTripped(rs);
        if (riskBlock) {
            try {
                int made = strategyService.generateAdvicesNow();
                if (made > 0) log.info("tick(): strategy generated {} advice(s)", made);
            } catch (Exception ex) {
                log.error("tick(): strategy generation failed: {}", ex.getMessage(), ex);
            }
        } else {
            log.debug("tick(): new entries blocked by flags; skipping strategy generation");
        }

        // -------- Portfolio-level protective step (always runs) --------
        try {
            manageProtectiveOrders();
        } catch (Exception ex) {
            log.error("tick(): manageProtectiveOrders failed: {}", ex.getMessage(), ex);
        }

        // -------- Lightweight UI refresh --------
        try {
            decisionService.getQuality();
        } catch (Exception ex) {
            log.error("tick(): decision refresh failed: {}", ex.getMessage(), ex);
        }
        try {
            riskService.getSummary();
        } catch (Exception ex) {
            log.error("tick(): risk refresh failed: {}", ex.getMessage(), ex);
        }
        try {
            sentimentService.getNow();
        } catch (Exception ex) {
            log.info("tick(): sentiment refresh failed: {}", ex.getMessage(), ex);
        }

        // -------- Re-strike manager (independent of new entry block) --------
        try {
            restrikeManagerTick();
        } catch (Exception ex) {
            log.error("tick(): re-strike manager failed: {}", ex.getMessage(), ex);
        }

        // -------- Execute pending advices (cap by maxExecPerTick) --------
        int executed = 0;
        try {
            List<Advice> pending = findPendingAdvices(scanLimit);
            log.debug("tick(): pending advices fetched={}", pending.size());

            for (int i = 0; i < pending.size() && executed < maxExecPerTick; i++) {
                Advice a = pending.get(i);
                if (a == null) continue;

                String id = a.getId();
                if (id == null) continue;

                try {
                    Result<?> r = adviceService.execute(id);
                    if (r != null && r.isOk()) {
                        executed++;
                        log.info("Advice executed: id={}", id);
                    } else {
                        String err = (r == null) ? "null result" : r.getError();
                        log.warn("Advice execution failed: id={}, error={}", id, err);
                    }
                } catch (Exception ex) {
                    log.error("tick(): advice id={} execution error: {}", id, ex.getMessage(), ex);
                }
            }
            log.info("tick(): executed {} advice(s) this tick (cap={})", executed, maxExecPerTick);
        } catch (Exception ex) {
            lastError = "Advice loop failed: " + ex.getMessage();
            log.error("tick(): advice loop error", ex);
        } finally {
            lastExecuted = executed;
            ticks.incrementAndGet();

            // Feed today's PnL into RiskService (circuit/budget)
            try {
                Result<PortfolioService.PortfolioSummary> ps = portfolioService.getPortfolioSummary();
                if (ps != null && ps.isOk() && ps.get() != null) {
                    Float day = toFloat(ps.get().getDayPnl());
                    float lossAbs = (day != null && day < 0f) ? -day : 0f;
                    riskService.updateDailyLossAbs(lossAbs);
                }
            } catch (Exception ex) {
                log.error("tick(): day PnL refresh failed: {}", ex.getMessage());
            }

            publishState();
        }

        engineExitTick();
    }

    private void publishState() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        try {
            EngineState state = getEngineState().get();
            log.info("engine.state -> running={}, ticks={}, lastExecuted={}, lastError={}",
                    state.running, state.ticks, state.lastExecuted, safe(lastError));
            stream.publish("engine.state", "engine", state);
        } catch (Exception ex) {
            log.error("publishState(): failed to emit engine.state: {}", ex.getMessage(), ex);
        }
    }

    // Add these methods inside EngineService:

    private List<Advice> findPendingAdvices(int limit) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return java.util.Collections.emptyList();
        }
        List<Advice> all = new ArrayList<>();
        try {
            List<Advice> fetched = adviceRepo.findAll();
            all.addAll(fetched);
        } catch (Exception ex) {
            log.error("findPendingAdvices(): repo fetch failed: {}", ex.getMessage(), ex);
        }
        if (all.isEmpty()) return java.util.Collections.emptyList();

        List<Advice> filtered = new ArrayList<>();
        for (Advice a : all) {
            if (a != null && a.getStatus() == AdviceStatus.PENDING) {
                filtered.add(a);
            }
        }
        filtered.sort((o1, o2) -> {
            Instant a1 = o1 == null ? null : o1.getCreatedAt();
            Instant a2 = o2 == null ? null : o2.getCreatedAt();
            if (a1 == null && a2 == null) return 0;
            if (a1 == null) return 1;
            if (a2 == null) return -1;
            return a2.compareTo(a1);
        });

        int cap = Math.max(1, limit);
        List<Advice> out = new ArrayList<>(Math.min(filtered.size(), cap));
        for (int i = 0; i < filtered.size() && i < cap; i++) out.add(filtered.get(i));
        return out;
    }

    private void registerExitPlanFromAdvice(Advice a) {
        if (a == null || a.getInstrument_token() == null || a.getReason() == null) return;
        Matcher m = EXIT_HINTS.matcher(a.getReason());
        if (!m.find()) return;

        Float sl = Float.valueOf(m.group(1));
        Float tp = Float.valueOf(m.group(2));
        int ttl = safeInt(m.group(3), 35);
        if (sl == null || tp == null) return;

        int qty = Optional.ofNullable(a.getQuantity()).orElse(0);
        if (qty <= 0) return;

        exitPlans.put(a.getInstrument_token(), new ExitPlan(a.getInstrument_token(), qty, sl, tp, ttl));
    }

    private void engineExitTick() {
        for (ExitPlan plan : exitPlans.values()) {
            try {
                if (Instant.now().isAfter(plan.expiresAt)) {
                    Float ltp = getLtp(plan.instrumentKey);
                    if (ltp != null && plan.slOrderId != null) {
                        ordersService.amendOrderPrice(plan.slOrderId, ltp);
                    }
                    continue;
                }

                Float entryEst = (plan.slInit != null) ? (plan.slInit / 0.75f) : null;
                Float ltp = getLtp(plan.instrumentKey);
                if (entryEst != null && ltp != null) {
                    float up20 = entryEst * 1.20f;
                    if (ltp >= up20) {
                        Float be = entryEst;
                        if (plan.slOrderId != null && plan.slLive != null && be > plan.slLive) {
                            ordersService.amendOrderPrice(plan.slOrderId, be);
                            plan.slLive = be;
                        }
                    }
                }

                boolean slWorking = plan.slOrderId != null && ordersService.isOrderWorking(plan.slOrderId).get();
                boolean tpWorking = plan.tpOrderId != null && ordersService.isOrderWorking(plan.tpOrderId).get();
                if (!slWorking && !tpWorking) {
                    exitPlans.remove(plan.instrumentKey);
                }
            } catch (Exception t) {
                // keep resilient
            }
        }
    }

    private Float getLtp(String instrumentKey) {
        try {
            GetMarketQuoteLastTradedPriceResponseV3 q = upstoxService.getMarketLTPQuote(instrumentKey);
            double px = q.getData().get(instrumentKey).getLastPrice();
            if (px <= 0) return null;
            float f = (float) px;
            return Math.round(f * 100f) / 100f;
        } catch (Exception t) {
            return null;
        }
    }

    public void onAdviceCreated(Advice a) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        try {
            registerExitPlanFromAdvice(a);
        } catch (Exception e) { /* keep resilient */ }
    }

    public void onAdviceExecuted(Advice a) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        try {
            if (a == null || a.getInstrument_token() == null) return;
            ExitPlan plan = exitPlans.get(a.getInstrument_token());
            if (plan == null) return;

            int qty = Optional.ofNullable(a.getQuantity()).orElse(plan.qty);

            if (plan.slOrderId == null && plan.slInit != null && qty > 0) {
                String id = ordersService.placeStopLossOrder(plan.instrumentKey, qty, plan.slInit)
                        .get().getData().getOrderId();
                plan.slOrderId = id;
                plan.slLive = plan.slInit;
            }
            if (plan.tpOrderId == null && plan.tpInit != null && qty > 0) {
                String id = ordersService.placeTargetOrder(plan.instrumentKey, qty, plan.tpInit)
                        .get().getData().getOrderId();
                plan.tpOrderId = id;
            }
        } catch (Exception e) {
            log.error("onAdviceExecuted(): failed to place SL/TP: {}", e);
        }
    }

    /**
     * Portfolio-level protective step:
     * - Reads aggregate P&L from PortfolioService.PortfolioSummary
     * - Feeds today's loss (if any) into RiskService.updateDailyLossAbs(...)
     * - Leaves per-position SL/TP management to a position-aware path
     */
    private void manageProtectiveOrders() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        try {
            Result<PortfolioService.PortfolioSummary> res = portfolioService.getPortfolioSummary();
            if (res == null || !res.isOk() || res.get() == null) {
                log.debug("manageProtectiveOrders(): portfolio summary not available");
                return;
            }
            PortfolioService.PortfolioSummary ps = res.get();
            Float dayPnl = toFloat(ps.getDayPnl());
            float dayLossAbs = (dayPnl != null && dayPnl < 0f) ? -dayPnl : 0f;
            riskService.updateDailyLossAbs(dayLossAbs);
            log.info("manageProtectiveOrders(): positions={}, dayPnl={}, fedLossAbs={}",
                    ps.getPositionsCount(), dayPnl, dayLossAbs);
        } catch (Exception ex) {
            log.error("manageProtectiveOrders(): failed: {}", ex.getMessage(), ex);
        }
    }

    // UPDATED: safe, conservative re-strike manager with extra triggers and atomic exit->enter
    private void restrikeManagerTick() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return;
        }
        try {
            // original logic unchanged except guard added
            Instant now = Instant.now();
            if (Duration.between(lastRestrikeCheckAt, now).toMinutes() < RESTRIKE_CHECK_MINUTES) return;
            lastRestrikeCheckAt = now;

            ZonedDateTime z = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
            int hhmm = z.getHour() * 100 + z.getMinute();
            if (hhmm >= DO_NOT_RESTRIKE_AFTER_HHMM) return;

            int hourKey = z.getYear() * 1000000 + z.getDayOfYear() * 100 + z.getHour();
            if (hourKey != restrikeHourKey) {
                restrikeHourKey = hourKey;
                restrikeCountThisHour = 0;
            }
            if (restrikeCountThisHour >= RESTRIKE_MAX_PER_HOUR) return;

            Result<List<Advice>> res = adviceService.list();
            if (res == null || !res.isOk() || res.get() == null) return;

            double spot = 0.0;
            try {
                GetMarketQuoteLastTradedPriceResponseV3 q = upstoxService.getMarketLTPQuote(underlyingKey);
                if (q != null && q.getData() != null && q.getData().get(underlyingKey) != null) {
                    spot = q.getData().get(underlyingKey).getLastPrice();
                }
            } catch (Exception ignored) {
                log.error(" restrikeManagerTick(): failed to fetch LTP", ignored);
            }
            if (spot <= 0) return;

            int atmStrike = computeAtmStrikeInt((float) spot, 50);

            Integer currDir = getCurrentDirScoreSafe();
            Float atrPct = getCurrentAtrPctSafe();
            AtrBand currBand = atrBandOf(atrPct, 0.30f, 1.00f);

            boolean dirFlipTrigger = dirFlipped(lastDirScoreForRestrike, currDir, 10);
            boolean atrBandTrigger = atrBandChanged(lastAtrBandForRestrike, currBand);

            int triggered = 0;

            for (Advice a : res.get()) {
                if (a == null || a.getStatus() != AdviceStatus.EXECUTED) continue;
                if (!"BUY".equalsIgnoreCase(a.getTransaction_type())) continue;
                if (a.getSymbol() == null || a.getInstrument_token() == null) continue;

                Integer k = parseStrikeFromSymbol(a.getSymbol());
                if (k == null) continue;

                int steps = Math.abs(k - atmStrike) / 50;
                boolean atmShiftTrigger = steps >= RESTRIKE_TRIGGER_ATM_SHIFT;

                if (atmShiftTrigger || dirFlipTrigger || atrBandTrigger) {
                    int qty = a.getQuantity() <= 0 ? 0 : a.getQuantity();

                    Advice exit = new Advice();
                    exit.setSymbol(a.getSymbol());
                    exit.setInstrument_token(a.getInstrument_token());
                    exit.setTransaction_type("SELL");
                    exit.setOrder_type("MARKET");
                    exit.setProduct("MIS");
                    exit.setValidity("DAY");
                    exit.setQuantity(qty > 0 ? qty : 50);
                    String tag = atmShiftTrigger ? ("ATM shift " + steps + " step(s)")
                            : dirFlipTrigger ? "DIR flip" : "ATR band change";
                    exit.setReason("RESTRIKE: " + tag);
                    exit.setStatus(AdviceStatus.PENDING);
                    exit.setCreatedAt(Instant.now());
                    exit.setUpdatedAt(Instant.now());

                    adviceService.create(exit);

                    triggered++;
                    restrikeCountThisHour++;
                    if (restrikeCountThisHour >= RESTRIKE_MAX_PER_HOUR) break;
                }
            }

            if (triggered > 0) {
                if (riskBlock()) {

                    try {
                        int made = strategyService.generateAdvicesNow();
                        log.info("restrike: exits={}, new-buys={}", triggered, made);
                    } catch (Exception ignored) {
                        log.error(" restrikeManagerTick(): failed to generate new advices", ignored);
                    }
                } else {
                    log.info("restrike: skipped new-buys due to risk/flags");
                }

            }

            lastDirScoreForRestrike = currDir;
            lastAtrBandForRestrike = currBand;

        } catch (Exception t) {
            log.info("restrikeManagerTick(): {}", t);
        }
    }


    /**
     * Whether risk headroom is exhausted and we must block new entries.
     * Delegates to the same logic used inside the tick() loop.
     * Java 8 only, no reflection. Includes login guard.
     */
    public boolean riskBlock() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return true;
        }
        try {
            Result<RiskSnapshot> res = riskService.getSummary();
            RiskSnapshot rs = (res != null && res.isOk()) ? res.get() : null;
            return isCircuitLikeTripped(rs);
        } catch (Throwable t) {
            log.warn("riskBlock(): failed to read RiskSnapshot: {}", t.toString());
            return true;
        }
    }

    // ---------------- Public guards for other services/UI ----------------

    private Integer parseStrikeFromSymbol(String sym) {
        if (sym == null) return null;
        try {
            String[] parts = sym.trim().split("\\s+");
            for (String p : parts) {
                if (p.matches("\\d{4,5}")) return Integer.parseInt(p);
            }
        } catch (Exception ignored) {
            log.error(" parseStrikeFromSymbol(): failed to parse strike from symbol {}", sym, ignored);
        }
        return null;
    }

    private AtrBand atrBandOf(Float atrPct, Float quietMaxPct, Float volatileMinPct) {
        if (atrPct == null) return AtrBand.NORMAL;
        if (quietMaxPct != null && atrPct <= quietMaxPct) return AtrBand.QUIET;
        if (volatileMinPct != null && atrPct >= volatileMinPct) return AtrBand.VOLATILE;
        return AtrBand.NORMAL;
    }

    private boolean dirFlipped(Integer prev, Integer curr, int thrAbs) {
        if (prev == null || curr == null) return false;
        boolean prevBull = prev >= +thrAbs, prevBear = prev <= -thrAbs;
        boolean currBull = curr >= +thrAbs, currBear = curr <= -thrAbs;
        return (prevBull && currBear) || (prevBear && currBull);
    }

    private boolean atrBandChanged(AtrBand before, AtrBand now) {
        return before != null && now != null && before != now;
    }

    private Integer getCurrentDirScoreSafe() {
        try {
            Result<DecisionQuality> r = decisionService.getQuality();
            return (r != null && r.isOk() && r.get() != null) ? r.get().getScore() : null;
        } catch (Exception t) {
            return null;
        }
    }

    /**
     * Compute ATR% from last 20×5m candles of the underlying (no dependency on MDS).
     */
    private Float getCurrentAtrPctSafe() {
        try {
            GetIntraDayCandleResponse ic = upstoxService.getIntradayCandleData(underlyingKey, "minutes", "5");
            List<List<Object>> cs = (ic == null) ? null : ic.getData().getCandles();
            if (cs == null || cs.size() < 21) return null;

            int len = cs.size();
            int lookback = 20;
            double sumTR = 0.0;

            final int HIGH_IDX = 2;
            final int LOW_IDX = 3;
            final int CLOSE_IDX = 4;

            for (int i = len - lookback; i < len; i++) {
                List<Object> cur = cs.get(i);
                List<Object> prev = cs.get(i - 1);
                double high = ((Number) cur.get(HIGH_IDX)).doubleValue();
                double low = ((Number) cur.get(LOW_IDX)).doubleValue();
                double close = ((Number) cur.get(CLOSE_IDX)).doubleValue();
                double prevClose = ((Number) prev.get(CLOSE_IDX)).doubleValue();

                double hl = high - low;
                double hp = Math.abs(high - prevClose);
                double lp = Math.abs(low - prevClose);
                sumTR += Math.max(hl, Math.max(hp, lp));
            }

            double atr = sumTR / lookback;
            double last = ((Number) cs.get(len - 1).get(CLOSE_IDX)).doubleValue();
            if (last <= 0.0) return null;
            float pct = (float) ((atr / last) * 100.0);
            return Math.round(pct * 100f) / 100f;
        } catch (Exception t) {
            return null;
        }
    }

    // ---------------- Audit helpers (Step-10: emit to Kafka 'audit') ----------------
    private void audit(String event, JsonObject data) {
        try {
            final JsonObject payload = new JsonObject();
            payload.addProperty("ts", java.time.Instant.now().toEpochMilli());
            payload.addProperty("ts_iso", java.time.Instant.now().toString());
            payload.addProperty("source", "engine");
            payload.addProperty("event", event);
            if (data != null) payload.add("data", data);
            if (bus != null) bus.publish(EventBusConfig.TOPIC_AUDIT, "engine", payload.toString());
        } catch (Throwable ignored) { /* best-effort */ }
    }

    private void auditLifecycleStarted() {
        try {
            final JsonObject d = new JsonObject();
            if (startedAt != null) d.addProperty("startedAt", startedAt.toString());
            d.addProperty("maxExecPerTick", maxExecPerTick);
            d.addProperty("scanLimit", scanLimit);
            if (underlyingKey != null) d.addProperty("underlying", underlyingKey);
            audit("engine.started", d);
        } catch (Throwable ignored) { /* no-op */ }
    }

    private void auditLifecycleStopped() {
        try {
            final JsonObject d = new JsonObject();
            final java.time.Instant now = java.time.Instant.now();
            d.addProperty("stoppedAt", now.toString());
            if (startedAt != null) {
                long up = java.time.Duration.between(startedAt, now).getSeconds();
                if (up < 0) up = 0;
                d.addProperty("uptimeSec", up);
            }
            d.addProperty("ticks", ticks.get());
            d.addProperty("lastExecuted", lastExecuted);
            audit("engine.stopped", d);
        } catch (Throwable ignored) { /* no-op */ }
    }

    private void auditCircuitChange(boolean tripped, RiskSnapshot rs) {
        try {
            final JsonObject d = new JsonObject();
            d.addProperty("circuit", tripped);
            if (rs != null) {
                if (rs.getRiskBudgetLeft() != null) d.addProperty("riskBudgetLeft", rs.getRiskBudgetLeft());
                if (rs.getDailyLossPct() != null) d.addProperty("dailyLossPct", rs.getDailyLossPct());
                if (rs.getOrdersPerMinPct() != null) d.addProperty("ordersPerMinPct", rs.getOrdersPerMinPct());
                if (rs.getLotsUsed() != null) d.addProperty("lotsUsed", rs.getLotsUsed());
                if (rs.getLotsCap() != null) d.addProperty("lotsCap", rs.getLotsCap());
            }
            // reason (best-effort)
            String reason = null;
            if (rs != null) {
                if (rs.getRiskBudgetLeft() != null && rs.getRiskBudgetLeft() <= 0.0)
                    reason = appendReason(reason, "budget_exhausted");
                if (rs.getDailyLossPct() != null && rs.getDailyLossPct() >= 100.0)
                    reason = appendReason(reason, "daily_loss_cap");
                Integer used = rs.getLotsUsed(), cap = rs.getLotsCap();
                if (used != null && cap != null && cap > 0 && used >= cap) reason = appendReason(reason, "lots_cap");
                if (rs.getOrdersPerMinPct() != null && rs.getOrdersPerMinPct() >= 100.0)
                    reason = appendReason(reason, "orders_per_min_cap");
            }
            if (reason != null) d.addProperty("reason", reason);
            audit(tripped ? "engine.circuit.open" : "engine.circuit.closed", d);
        } catch (Throwable ignored) { /* no-op */ }
    }

    private String appendReason(String base, String add) {
        if (add == null || add.length() == 0) return base;
        if (base == null || base.length() == 0) return add;
        return base + "," + add;
    }

    private boolean isCircuitLikeTripped(RiskSnapshot rs) {
        if (rs == null) return false;

        // 1) Out of budget or fully exhausted daily loss
        if (rs.getRiskBudgetLeft() <= 0.0) return true;
        if (rs.getDailyLossPct() >= 100.0) return true;

        // 2) Lots guardrail breached
        Integer used = rs.getLotsUsed();
        Integer cap = rs.getLotsCap();
        if (used != null && cap != null && cap > 0 && used >= cap) return true;

        // 3) Order rate limiter blown
        if (rs.getOrdersPerMinPct() >= 100.0) return true;

        return false;
    }

    private enum AtrBand {QUIET, NORMAL, VOLATILE}

    // DTO to surface engine→strategy/decision inputs each cycle
    @Data
    public static class EngineInputs {
        private boolean riskHeadroomOk;
        private int minutesSinceLastSl;
        private int restrikesToday;
        private Double ordersPerMinPct;
    }

    @Data
    public static class EngineState {
        private boolean running;
        private Instant startedAt;
        private Instant lastTick;
        private long ticks;
        private long lastExecuted;
        private String lastError;
        private Instant asOf;

        public EngineState(boolean b, Instant startedAt, Instant lastTickAt, long l, long lastExecuted, String lastError, Instant now) {
            this.running = b;
            this.startedAt = startedAt;
            this.lastTick = lastTickAt;
            this.ticks = l;
            this.lastExecuted = lastExecuted;
            this.lastError = lastError;
            this.asOf = now;
        }
    }

    // Lightweight POJO purely for SSE; not stored anywhere and not part of your API surface.
    @Data
    @AllArgsConstructor
    public static class EngineHeartbeat {
        private Instant asOf;
        private int sentimentScore;
        private String sentimentLabel;
        private int decisionScore;
        private String trend;
        private List<String> tags;
        private List<String> reasons;

        public EngineHeartbeat() {
        }
    }

    private static class ExitPlan {
        final String instrumentKey;
        final int qty;
        final Float slInit;
        final Float tpInit;
        final Instant createdAt;
        final Instant expiresAt;
        Float slLive;
        String slOrderId;
        String tpOrderId;

        ExitPlan(String instrumentKey, int qty, Float sl, Float tp, int ttlMin) {
            this.instrumentKey = instrumentKey;
            this.qty = qty;
            this.slInit = sl;
            this.tpInit = tp;
            this.slLive = sl;
            this.createdAt = Instant.now();
            this.expiresAt = this.createdAt.plus(Duration.ofMinutes(Math.max(1, ttlMin)));
        }
    }

    private static String safe(String s) {
        if (s == null) return "—";
        String t = s.trim();
        return t.isEmpty() ? "—" : t;
    }

    private static int safeInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static Float toFloat(java.math.BigDecimal v) {
        return v == null ? null : v.floatValue();
    }
}