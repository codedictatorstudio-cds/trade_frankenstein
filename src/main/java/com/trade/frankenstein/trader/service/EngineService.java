package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    @Value("${trade.engine.max-exec-per-tick:3}")
    private int maxExecPerTick;

    @Value("${trade.engine.scan-limit:50}")
    private int scanLimit;

    /**
     * Default underlying used by analysis services (keep in sync with others).
     */
    @Value("${trade.nifty-underlying-key:NFO:NIFTY50-INDEX}")
    private String underlyingKey;

    /**
     * Analysis tick cadence (ms). Keep modest; services pull live data already.
     */
    @Value("${trade.engine.analysis-ms:15000}")
    private long analysisMs;

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
            publishState();
        }
    }

    @Scheduled(fixedDelayString = "${trade.engine.analysis-ms:15000}")
    public void runAnalysisTick() {
        try {
            // 1) Real-time sentiment refresh (persists + broadcasts on "sentiment.update")
            MarketSentimentSnapshot snap = null;
            try {
                snap = sentimentService.captureRealtimeNow();
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
}
