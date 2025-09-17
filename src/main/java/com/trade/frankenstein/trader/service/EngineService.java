package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.model.documents.DecisionQuality;
import com.trade.frankenstein.trader.model.documents.MarketSentimentSnapshot;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.repo.documents.AdviceRepo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
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
        } catch (Throwable ex) {
            log.info("tick(): risk broker-PnL refresh failed: {}", ex.getMessage(), ex);
        }

        try {
            int made = strategyService.generateAdvicesNow();
            if (made > 0) log.info("tick(): strategy generated {} advice(s)", made);
        } catch (Throwable ex) {
            log.info("tick(): strategy generation failed: {}", ex.getMessage(), ex);
        }

        // Still inside tick(), after the loop that executes advices:
        try {
            manageProtectiveOrders();   // see stub below
        } catch (Throwable ex) {
            log.info("tick(): manageProtectiveOrders failed: {}", ex.getMessage(), ex);
        }


        // Market-hours and circuit checks
        try {
            Result<RiskSnapshot> circuitRes = riskService.getSummary();
            RiskSnapshot circuit = (circuitRes == null) ? null : circuitRes.get();
            if (circuit != null) {
                boolean tripped = reflectBoolean(circuit, "isTripped", "tripped");
                if (tripped) {
                    String reason = reflectString(circuit, "getReason", "reason");
                    lastError = "Circuit tripped" + (reason == null ? "" : (": " + reason));
                    log.warn("tick(): {} — skipping execution", lastError);
                    ticks.incrementAndGet();
                    publishState();
                    return;
                }
            }
        } catch (Throwable ex) {
            lastError = "Pre-checks failed: " + ex.getMessage();
            log.warn("tick(): pre-checks error", ex);
        }

        // Refresh UI signals (lightweight)
        try {
            decisionService.getQuality();
        } catch (Throwable ex) {
            log.info("tick(): decision refresh failed: {}", ex.getMessage(), ex);
        }
        try {
            riskService.getSummary();
        } catch (Throwable ex) {
            log.info("tick(): risk refresh failed: {}", ex.getMessage(), ex);
        }
        try {
            sentimentService.getNow();
        } catch (Throwable ex) {
            log.info("tick(): sentiment refresh failed: {}", ex.getMessage(), ex);
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
                } catch (Throwable ex) {
                    log.warn("tick(): advice id={} execution error: {}", id, ex.getMessage(), ex);
                }
            }
            log.info("tick(): executed {} advice(s) this tick (cap={})", executed, maxExecPerTick);
        } catch (Throwable ex) {
            lastError = "Advice loop failed: " + ex.getMessage();
            log.warn("tick(): advice loop error", ex);
        } finally {
            lastExecuted = executed;
            ticks.incrementAndGet();

            // -- Live day PnL feed into RiskService (for circuit/budget) --
            try {
                Result<PortfolioService.PortfolioSummary> ps = portfolioService.getPortfolioSummary();
                if (ps != null && ps.isOk() && ps.get() != null) {
                    BigDecimal day = ps.get().getDayPnl();
                    BigDecimal lossAbs = (day != null && day.signum() < 0) ? day.abs() : BigDecimal.ZERO;
                    riskService.updateDailyLossAbs(lossAbs);
                }
            } catch (Throwable ex) {
                log.debug("tick(): day PnL refresh failed: {}", ex.getMessage());
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
            } catch (Throwable t) {
                log.debug("Engine: sentiment refresh failed: {}", t.getMessage());
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
            } catch (Throwable ignored) {
                log.info(" Engine: heartbeat stream error: {}", ignored.getMessage());
            }

        } catch (Throwable t) {
            log.warn("Engine tick failed", t);
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
        } catch (Throwable ex) {
            log.info("publishState(): failed to emit engine.state: {}", ex.getMessage(), ex);
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
        } catch (Throwable ex) {
            log.warn("findPendingAdvices(): repo fetch failed: {}", ex.getMessage(), ex);
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

    // Small reflect helpers to avoid hard-coupling to Risk DTO shape
    private boolean reflectBoolean(Object obj, String... getters) {
        if (obj == null) return false;
        for (String getter : getters) {
            try {
                Object v = obj.getClass().getMethod(getter).invoke(obj);
                if (v instanceof Boolean) return (Boolean) v;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private String reflectString(Object obj, String... getters) {
        if (obj == null) return null;
        for (String getter : getters) {
            try {
                Object v = obj.getClass().getMethod(getter).invoke(obj);
                if (v != null) return String.valueOf(v);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void registerExitPlanFromAdvice(Advice a) {
        if (a == null || a.getInstrument_token() == null || a.getReason() == null) return;
        Matcher m = EXIT_HINTS.matcher(a.getReason());
        if (!m.find()) return;

        BigDecimal sl = toBd(m.group(1));
        BigDecimal tp = toBd(m.group(2));
        int ttl = safeInt(m.group(3), 35);
        if (sl == null || tp == null) return;

        // use Advice.quantity as the working qty
        int qty = Optional.ofNullable(a.getQuantity()).orElse(0);
        if (qty <= 0) return;

        exitPlans.put(a.getInstrument_token(), new ExitPlan(a.getInstrument_token(), qty, sl, tp, ttl));
    }

    private static BigDecimal toBd(String s) {
        if (s == null) return null;
        try {
            String t = s.trim().replaceAll("[^0-9.\\-]", "");
            if (t.isEmpty()) return null;
            return new BigDecimal(t).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
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
                    BigDecimal ltp = getLtp(plan.instrumentKey);
                    if (ltp != null && plan.slOrderId != null) {
                        // move SL to current LTP to trigger an immediate exit
                        ordersService.amendOrderPrice(plan.slOrderId, ltp);
                    }
                    // after forcing, we can let normal order-state listeners clean up the plan
                    continue;
                }

                // 2) Simple trailing: if in profit ≥ +20% from implied entry, trail SL to breakeven
                // infer approximate entry from SL hint (slInit ≈ entry * (1 - 0.25)) ⇒ entry ≈ slInit / 0.75
                BigDecimal entryEst = (plan.slInit != null) ? plan.slInit.divide(new BigDecimal("0.75"), 2, RoundingMode.HALF_UP) : null;
                BigDecimal ltp = getLtp(plan.instrumentKey);
                if (entryEst != null && ltp != null) {
                    BigDecimal up20 = entryEst.multiply(new BigDecimal("1.20"));
                    if (ltp.compareTo(up20) >= 0) {
                        BigDecimal be = entryEst; // move SL to breakeven
                        if (plan.slOrderId != null && plan.slLive != null && be.compareTo(plan.slLive) > 0) {
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
            } catch (Throwable t) {
                // keep engine resilient—do not throw
            }
        }
    }

    private BigDecimal getLtp(String instrumentKey) {
        try {
            var q = upstoxService.getMarketLTPQuote(instrumentKey);
            double px = q.getData().get(instrumentKey).getLast_price();
            return px > 0 ? new BigDecimal(px).setScale(2, RoundingMode.HALF_UP) : null;
        } catch (Throwable t) {
            return null;
        }
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
                        .get().getData().getOrder_ids().stream().findFirst().get();
                plan.slOrderId = id;
                plan.slLive = plan.slInit;
            }

            // Place TP if not placed yet
            if (plan.tpOrderId == null && plan.tpInit != null && qty > 0) {
                String id = ordersService.placeTargetOrder(plan.instrumentKey, qty, plan.tpInit)
                        .get().getData().getOrder_ids().stream().findFirst().get();
                plan.tpOrderId = id;
            }
        } catch (Exception e) {
            // keep engine resilient
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
        final BigDecimal slInit;
        final BigDecimal tpInit;
        final Instant createdAt;
        final Instant expiresAt;
        BigDecimal slLive;
        String slOrderId;
        String tpOrderId;

        ExitPlan(String instrumentKey, int qty, BigDecimal sl, BigDecimal tp, int ttlMin) {
            this.instrumentKey = instrumentKey;
            this.qty = qty;
            this.slInit = sl;
            this.tpInit = tp;
            this.slLive = sl;
            this.createdAt = Instant.now();
            this.expiresAt = this.createdAt.plus(Duration.ofMinutes(Math.max(1, ttlMin)));
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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

            BigDecimal dayPnl = nz(ps.getDayPnl());
            BigDecimal dayPnlPct = nz(ps.getDayPnlPct());
            int positions = ps.getPositionsCount();

            // Convert portfolio readout to absolute daily loss for RiskService
            BigDecimal dayLossAbs = dayPnl.signum() < 0 ? dayPnl.abs() : BigDecimal.ZERO;
            riskService.updateDailyLossAbs(dayLossAbs);

            log.info("manageProtectiveOrders(): positions={}, dayPnl={}, dayPnlPct={}, lossAbsFedToRisk={}",
                    positions, dayPnl, dayPnlPct, dayLossAbs);

            // If your RiskService auto-trips the circuit on updateDailyLossAbs() thresholds,
            // nothing more is needed here. Engine tick will naturally respect the circuit state.
            // If you want a hard stop when any loss is detected and positions are open, uncomment:
            // if (positions > 0 && dayLossAbs.compareTo(BigDecimal.ZERO) > 0 && riskService.isDailyCircuitTripped()) {
            //     log.warn("Circuit is tripped with open positions; execution will be skipped by engine guards.");
            // }


        } catch (Throwable ex) {
            log.debug("manageProtectiveOrders(): failed: {}", ex.getMessage(), ex);
        }
    }

}
