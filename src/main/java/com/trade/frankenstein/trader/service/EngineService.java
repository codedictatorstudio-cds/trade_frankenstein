package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.model.documents.DecisionQuality;
import com.trade.frankenstein.trader.model.documents.MarketSentimentSnapshot;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.repo.documents.AdviceRepo;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("ALL")
@Service
@Slf4j
public class EngineService {

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

    private final Map<String, ExitPlan> exitPlans = new ConcurrentHashMap<>(); // key = instrument_key
    private static final Pattern EXIT_HINTS = Pattern.compile("EXIT:\\s*SL=([^,]+),\\s*TP=([^,]+),\\s*TTL=(\\d+)m", Pattern.CASE_INSENSITIVE);

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

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong ticks = new AtomicLong(0);

    private volatile Instant startedAt = null;
    private volatile Instant lastTickAt = null;
    private volatile long lastExecuted = 0;
    private volatile String lastError = null;

    // --- Re-strike knobs (safe defaults) ---
    private static final int RESTRIKE_CHECK_MINUTES = 5;
    private static final int RESTRIKE_TRIGGER_ATM_SHIFT = 1; // strike steps (50-pt)
    private static final int RESTRIKE_MAX_PER_HOUR = 2;
    private static final int DO_NOT_RESTRIKE_AFTER_HHMM = 1500; // 15:00 IST

    // Re-strike state
    private volatile Instant lastRestrikeCheckAt = Instant.EPOCH;
    private volatile int restrikeCountThisHour = 0;
    private volatile int restrikeHourKey = -1;

    // Re-strike memory for change-detection
    private volatile Integer lastDirScoreForRestrike = null;

    private enum AtrBand {QUIET, NORMAL, VOLATILE}

    private volatile AtrBand lastAtrBandForRestrike = null;

    // ---------------- Lifecycle ----------------
    public Result<String> startEngine() {
        if (running.get()) {
            log.warn("Engine start requested but already running");
            return Result.ok("engine:already-running");
        }
        running.set(true);
        if (startedAt == null) startedAt = Instant.now();
        log.info("Engine STARTED at {} (maxExecPerTick={}, scanLimit={})",
                startedAt, maxExecPerTick, scanLimit);
        publishState();
        return Result.ok("engine:started");
    }

    public Result<String> stopEngine() {
        if (!running.get()) {
            log.warn("Engine stop requested but already stopped");
            return Result.ok("engine:already-stopped");
        }
        running.set(false);
        log.info("Engine STOPPED");
        publishState();
        return Result.ok("engine:stopped");
    }

    public Result<EngineState> getEngineState() {
        EngineState state = new EngineState(
                running.get(), startedAt, lastTickAt, ticks.get(), lastExecuted, lastError, Instant.now()
        );
        return Result.ok(state);
    }

    // ---------------- Tick Loop ----------------
    @Scheduled(fixedDelayString = "${trade.engine.tick-ms:2000}")
    public void tick() {
        if (!running.get()) {
            log.debug("tick(): engine not running, skip");
            return;
        }

        lastTickAt = Instant.now();
        lastExecuted = 0;
        lastError = null;

        try {
            riskService.refreshDailyLossFromBroker();
        } catch (Exception ex) {
            log.info("tick(): risk broker-PnL refresh failed: {}", ex.getMessage(), ex);
        }

        try {
            int made = strategyService.generateAdvicesNow();
            if (made > 0) log.info("tick(): strategy generated {} advice(s)", made);
        } catch (Exception ex) {
            log.error("tick(): strategy generation failed: {}", ex.getMessage(), ex);
        }

        // Still inside tick(), after the loop that executes advices:
        try {
            manageProtectiveOrders();   // see stub below
        } catch (Exception ex) {
            log.error("tick(): manageProtectiveOrders failed: {}", ex.getMessage(), ex);
        }

        // UPDATED pre-check in tick()
        try {
            Result<RiskSnapshot> res = riskService.getSummary();
            RiskSnapshot r = (res != null && res.isOk()) ? res.get() : null;

            boolean block = false;
            StringBuilder why = new StringBuilder();

            if (r != null) {
                // 1) Budget exhausted
                if (r.getRiskBudgetLeft() <= 0.0) {
                    block = true;
                    appendReason(why, "Risk budget exhausted");
                }
                // 2) Lots cap reached
                Integer used = r.getLotsUsed();
                Integer cap = r.getLotsCap();
                if (!block && used != null && cap != null && used >= cap) {
                    block = true;
                    appendReason(why, "Lots cap reached (" + used + "/" + cap + ")");
                }
                // 3) Daily loss throttle at 100%
                if (!block && r.getDailyLossPct() >= 100.0) {
                    block = true;
                    appendReason(why, "Daily loss at 100%");
                }
                // 4) Orders/min throttle at 100%
                if (!block && r.getOrdersPerMinPct() >= 100.0) {
                    block = true;
                    appendReason(why, "Orders/min limit at 100%");
                }
            }

            if (block) {
                lastError = why.length() == 0 ? "Risk guard: blocked" : why.toString();
                log.warn("tick(): {} — skipping execution", lastError);
                ticks.incrementAndGet();
                publishState();
                return;
            }
        } catch (Exception ex) {
            lastError = "Pre-checks failed: " + ex.getMessage();
            log.error("tick(): pre-checks error", ex);
        }


        // Refresh UI signals (lightweight)
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
        try {
            restrikeManagerTick();
        } catch (Exception ex) {
            log.error("tick(): re-strike manager failed: {}", ex.getMessage(), ex);
        }
        // Execute pending advices (newest first), capped by maxExecPerTick
        int executed = 0;
        try {
            List<Advice> pending = findPendingAdvices(scanLimit);
            log.debug("tick(): pending advices fetched={}", pending.size());

            for (int i = 0; i < pending.size() && executed < maxExecPerTick; i++) {
                Advice a = pending.get(i);
                String id = a == null ? null : a.getId();
                if (id == null) continue;

                try {
                    Result<?> r = adviceService.execute(id);   // String id (Mongo)
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

            // -- Live day PnL feed into RiskService (for circuit/budget) --
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

    @Scheduled(fixedDelayString = "${trade.engine.analysis-ms:15000}")
    public void runAnalysisTick() {
        try {
            // 1) Real-time sentiment refresh (persists + broadcasts on "sentiment.update")
            MarketSentimentSnapshot snap = null;
            try {
                snap = sentimentService.getNow().get();
            } catch (Exception t) {
                log.error("Engine: sentiment refresh failed: {}", t.getMessage());
            }

            // 2) Decision quality (pulls live PCR / momentum / slippage via dependencies)
            Result<DecisionQuality> qRes = decisionService.getQuality();

            // 3) Build heartbeat (no new DTOs exposed; we use a tiny inner POJO for SSE only)
            EngineHeartbeat hb = new EngineHeartbeat();
            hb.setAsOf(Instant.now());

            if (snap != null) {
                hb.setSentimentScore(nzi(snap.getScore()));
                hb.setSentimentLabel(nullSafe(snap.getSentiment()));
            }

            if (qRes != null && qRes.isOk() && qRes.get() != null) {
                DecisionQuality q = qRes.get();
                hb.setDecisionScore(q.getScore());
                hb.setTrend(nullSafe(q.getTrend().name()));
                hb.setTags(List.of(q.getRr(), q.getSlippage(), q.getThrottle()));
                hb.setReasons(q.getReasons());
            }

            // 4) Push engine heartbeat (non-blocking)
            try {
                stream.send("engine.heartbeat", hb);
            } catch (Exception ignored) {
                log.error(" Engine: heartbeat stream error: {}", ignored);
            }

        } catch (Exception t) {
            log.error("Engine tick failed", t);
        }
    }

    // ---------------- Helpers ----------------

    private void publishState() {
        try {
            EngineState state = getEngineState().get();
            log.info("engine.state -> running={}, ticks={}, lastExecuted={}, lastError={}",
                    state.isRunning(), state.getTicks(), state.getLastExecuted(), safe(lastError));
            // Sticky so new SSE subscribers get the latest instantly
            stream.sendSticky("engine.state", state);
        } catch (Exception ex) {
            log.error("publishState(): failed to emit engine.state: {}", ex.getMessage(), ex);
        }
    }

    private static String safe(String s) {
        if (s == null) return "—";
        String t = s.trim();
        return t.isEmpty() ? "—" : t;
    }


    private List<Advice> findPendingAdvices(int limit) {
        List<Advice> all = new ArrayList<>();
        try {
            List<Advice> fetched = adviceRepo.findAll();
            all.addAll(fetched);
        } catch (Exception ex) {
            log.error("findPendingAdvices(): repo fetch failed: {}", ex.getMessage(), ex);
        }
        if (all.isEmpty()) return Collections.emptyList();

        // Filter PENDING, sort by createdAt desc, cap to limit
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
            return a2.compareTo(a1); // newest first
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

        // use Advice.quantity as the working qty
        int qty = Optional.ofNullable(a.getQuantity()).orElse(0);
        if (qty <= 0) return;

        exitPlans.put(a.getInstrument_token(), new ExitPlan(a.getInstrument_token(), qty, sl, tp, ttl));
    }


    private static int safeInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private void engineExitTick() {
        for (ExitPlan plan : exitPlans.values()) {
            try {
                // 1) Time-stop: if expired, force exit by moving SL to (≈) market
                if (Instant.now().isAfter(plan.expiresAt)) {
                    Float ltp = getLtp(plan.instrumentKey);
                    if (ltp != null && plan.slOrderId != null) {
                        // move SL to current LTP to trigger an immediate exit
                        ordersService.amendOrderPrice(plan.slOrderId, ltp);
                    }
                    // after forcing, we can let normal order-state listeners clean up the plan
                    continue;
                }

                // 2) Simple trailing: if in profit ≥ +20% from implied entry, trail SL to breakeven
                // infer approximate entry from SL hint (slInit ≈ entry * (1 - 0.25)) ⇒ entry ≈ slInit / 0.75
                Float entryEst = (plan.slInit != null) ? (plan.slInit / 0.75f) : null;

                Float ltp = getLtp(plan.instrumentKey);
                if (entryEst != null && ltp != null) {
                    float up20 = entryEst * 1.20f;
                    if (ltp >= up20) {
                        Float be = entryEst; // move SL to breakeven
                        if (plan.slOrderId != null && plan.slLive != null && be > plan.slLive) {
                            ordersService.amendOrderPrice(plan.slOrderId, be);
                            plan.slLive = be;
                        }
                    }
                }

                // 3) Clean-up heuristic: if neither SL nor TP order is working anymore, drop plan
                boolean slWorking = plan.slOrderId != null && ordersService.isOrderWorking(plan.slOrderId).get();
                boolean tpWorking = plan.tpOrderId != null && ordersService.isOrderWorking(plan.tpOrderId).get();
                if (!slWorking && !tpWorking) {
                    exitPlans.remove(plan.instrumentKey);
                }
            } catch (Exception t) {
                // keep engine resilient—do not throw
            }
        }
    }

    private Float getLtp(String instrumentKey) {
        try {
            var q = upstoxService.getMarketLTPQuote(instrumentKey);
            double px = q.getData().get(instrumentKey).getLastPrice();
            if (px <= 0) return null;
            float f = (float) px;
            return round2(f);
        } catch (Exception t) {
            return null;
        }
    }

    private static float round2(float v) {
        return Math.round(v * 100f) / 100f;
    }

    // Add these methods inside EngineService:

    public void onAdviceCreated(Advice a) {
        try {
            // Parse and register EXIT: SL/TP/TTL hints from Advice.reason
            registerExitPlanFromAdvice(a);
        } catch (Exception e) {
            // keep engine resilient
        }
    }

    public void onAdviceExecuted(Advice a) {
        try {
            if (a == null || a.getInstrument_token() == null) return;
            ExitPlan plan = exitPlans.get(a.getInstrument_token());
            if (plan == null) return;

            // Use advice quantity if provided (fallback to plan qty)
            int qty = Optional.ofNullable(a.getQuantity()).orElse(plan.qty);

            // Place SL if not placed yet
            if (plan.slOrderId == null && plan.slInit != null && qty > 0) {
                String id = ordersService.placeStopLossOrder(plan.instrumentKey, qty, plan.slInit)
                        .get().getData().getOrderId();
                plan.slOrderId = id;
                plan.slLive = plan.slInit;
            }

            // Place TP if not placed yet
            if (plan.tpOrderId == null && plan.tpInit != null && qty > 0) {
                String id = ordersService.placeTargetOrder(plan.instrumentKey, qty, plan.tpInit)
                        .get().getData().getOrderId();
                plan.tpOrderId = id;
            }
        } catch (Exception e) {
            log.error("onAdviceExecuted(): failed to place SL/TP: {}", e);
        }
    }

    // ---------------- DTO ----------------
    @Data
    public static class EngineState {
        private final boolean running;
        private final Instant startedAt;
        private final Instant lastTick;
        private final long ticks;
        private final long lastExecuted;
        private final String lastError;
        private final Instant asOf;
    }

    private static int nzi(Integer v) {
        return v == null ? 0 : v;
    }

    private static String nullSafe(String s) {
        return (s == null || s.trim().isEmpty()) ? "—" : s.trim();
    }

    // Lightweight POJO purely for SSE; not stored anywhere and not part of your API surface.
    @Data
    @AllArgsConstructor
    public static class EngineHeartbeat {
        public EngineHeartbeat() {
        }

        private Instant asOf;
        private int sentimentScore;
        private String sentimentLabel;
        private int decisionScore;
        private String trend;
        private List<String> tags;
        private List<String> reasons;
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

    /**
     * Portfolio-level protective step:
     * - Reads aggregate P&L from PortfolioService.PortfolioSummary
     * - Feeds today's loss (if any) into RiskService.updateDailyLossAbs(...)
     * - Leaves per-position SL/TP management to a position-aware path
     */
    private void manageProtectiveOrders() {
        try {
            Result<PortfolioService.PortfolioSummary> res = portfolioService.getPortfolioSummary();
            if (res == null || !res.isOk() || res.get() == null) {
                log.debug("manageProtectiveOrders(): portfolio summary not available");
                return;
            }

            PortfolioService.PortfolioSummary ps = res.get();

            Float dayPnl = toFloat(ps.getDayPnl());
            Float dayPnlPct = toFloat(ps.getDayPnlPct());
            int positions = ps.getPositionsCount();

            // Convert portfolio readout to absolute daily loss for RiskService
            float dayLossAbs = (dayPnl != null && dayPnl < 0f) ? -dayPnl : 0f;
            riskService.updateDailyLossAbs(dayLossAbs);

            log.info("manageProtectiveOrders(): positions={}, dayPnl={}, dayPnlPct={}, lossAbsFedToRisk={}",
                    positions, dayPnl, dayPnlPct, dayLossAbs);

            // If your RiskService auto-trips the circuit on updateDailyLossAbs() thresholds,
            // nothing more is needed here. Engine tick will naturally respect the circuit state.
            // If you want a hard stop when any loss is detected and positions are open, uncomment:
            // if (positions > 0 && dayLossAbs > 0f && riskService.isDailyCircuitTripped()) {
            //     log.warn("Circuit is tripped with open positions; execution will be skipped by engine guards.");
            // }


        } catch (Exception ex) {
            log.error("manageProtectiveOrders(): failed: {}", ex.getMessage(), ex);
        }
    }

    // UPDATED: safe, conservative re-strike manager with extra triggers and atomic exit->enter
    private void restrikeManagerTick() {
        try {
            // throttle checks
            Instant now = Instant.now();
            if (Duration.between(lastRestrikeCheckAt, now).toMinutes() < RESTRIKE_CHECK_MINUTES) return;
            lastRestrikeCheckAt = now;

            // cut-off by time (IST)
            ZonedDateTime z = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
            int hhmm = z.getHour() * 100 + z.getMinute();
            if (hhmm >= DO_NOT_RESTRIKE_AFTER_HHMM) return;

            // per-hour limit
            int hourKey = z.getYear() * 1000000 + z.getDayOfYear() * 100 + z.getHour();
            if (hourKey != restrikeHourKey) {
                restrikeHourKey = hourKey;
                restrikeCountThisHour = 0;
            }
            if (restrikeCountThisHour >= RESTRIKE_MAX_PER_HOUR) return;

            // prerequisites
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

            // --- new change-detection signals ---
            Integer currDir = getCurrentDirScoreSafe();
            Float atrPct = getCurrentAtrPctSafe();
            // Use the same thresholds you already use elsewhere (quiet ≤0.30%, volatile ≥1.00%).
            AtrBand currBand = atrBandOf(atrPct, 0.30f, 1.00f);

            boolean dirFlipTrigger = dirFlipped(lastDirScoreForRestrike, currDir, 10); // |score| ≥ 10 = directional
            boolean atrBandTrigger = atrBandChanged(lastAtrBandForRestrike, currBand);

            int triggered = 0;

            // --- existing ATM-shift trigger (per executed long leg) ---
            for (Advice a : res.get()) {
                if (a == null || a.getStatus() != AdviceStatus.EXECUTED) continue;
                if (!"BUY".equalsIgnoreCase(a.getTransaction_type())) continue;
                if (a.getSymbol() == null || a.getInstrument_token() == null) continue;

                Integer k = parseStrikeFromSymbol(a.getSymbol());
                if (k == null) continue;

                int steps = Math.abs(k - atmStrike) / 50;
                boolean atmShiftTrigger = steps >= RESTRIKE_TRIGGER_ATM_SHIFT;

                // fire if any trigger is true
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

            // immediately enter new legs after exits (atomic roll)
            if (triggered > 0) {
                try {
                    int made = strategyService.generateAdvicesNow();
                    log.info("restrike: exits={}, new-buys={}", triggered, made);
                } catch (Exception ignored) {
                    log.error(" restrikeManagerTick(): failed to generate new advices", ignored);
                }
            }

            // remember current signals
            lastDirScoreForRestrike = currDir;
            lastAtrBandForRestrike = currBand;

        } catch (Exception t) {
            log.info("restrikeManagerTick(): {}", t);
        }
    }


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
            if (cs == null || cs.size() < 21) return null; // need >= 21 to have 20 TRs

            // ATR over last 20 bars (simple average TR)
            int len = cs.size();
            int lookback = 20;
            double sumTR = 0.0;

            // Assuming each candle is [timestamp, open, high, low, close, volume, ...]
            // Indices for high, low, close in the candle list
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
            return round2(pct);
        } catch (Exception t) {
            return null;
        }
    }

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

    private static Float toFloat(java.math.BigDecimal v) {
        return v == null ? null : v.floatValue();
    }

}
