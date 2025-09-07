package com.trade.frankenstein.trader.service.execution;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.entity.AdviceEntity;
import com.trade.frankenstein.trader.repo.AdviceRepository;
import com.trade.frankenstein.trader.service.advice.AdviceService;
import com.trade.frankenstein.trader.service.decision.DecisionService;
import com.trade.frankenstein.trader.service.risk.RiskService;
import com.trade.frankenstein.trader.service.sentiment.SentimentService;
import com.trade.frankenstein.trader.service.signals.MarketSignalsService;
import com.trade.frankenstein.trader.service.streaming.StreamGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class EngineService {

    private final StreamGateway stream;
    private final SentimentService sentimentService;
    private final DecisionService decisionService;
    private final AdviceService adviceService;
    private final AdviceRepository adviceRepo;
    private final RiskService riskService; // now actively used
    private final MarketSignalsService marketSignalsService;

    @Value("${trade.engine.max-exec-per-tick:3}")
    private int maxExecPerTick;
    @Value("${trade.engine.scan-limit:50}")
    private int scanLimit;
    @Value(("${trade.engine.max-exec-per-tick:3}"))
    private int max;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong ticks = new AtomicLong(0);
    private volatile Instant startedAt = null;
    private volatile Instant lastTickAt = null;
    private volatile long lastExecuted = 0;
    private volatile String lastError = null;

    public Result<String> startEngine() {
        running.set(true);
        if (startedAt == null) startedAt = Instant.now();
        publishState();
        return Result.ok("engine:started");
    }

    public Result<String> stopEngine() {
        running.set(false);
        publishState();
        return Result.ok("engine:stopped");
    }

    public Result<EngineState> getEngineState() {
        return Result.ok(new EngineState(running.get(), startedAt, lastTickAt, ticks.get(), lastExecuted, lastError, Instant.now()));
    }

    @Scheduled(fixedDelayString = "${trade.engine.tick-ms:2000}")
    @Transactional
    public void tick() {
        if (!running.get()) return;

        lastTickAt = Instant.now();
        lastExecuted = 0;
        lastError = null;

        try {
            // Market hours guard
            if (!inMarketHours()) return;
            if (riskService.getCircuitState().getData().tripped()) return;

            marketSignalsService.refreshRegime();
        } catch (Throwable t) {
            log.warn("Regime refresh failed: {}", t.getMessage());
        }

        try {
            decisionService.generateAdvice();
        } catch (Exception ignored) {
            // keep the engine resilient
        }

        try {
            // Early risk gate (skip entire tick if circuit is tripped)
            var circuit = riskService.getCircuitState().get();
            if (circuit.tripped()) {
                lastError = "Circuit tripped: " + circuit.reason();
                ticks.incrementAndGet();
                publishState();
                return;
            }

            // Keep cards fresh
            try {
                riskService.getSummary();
                sentimentService.getNow();
                decisionService.getQuality();
            } catch (Throwable t) {
                log.info("Engine tick: status refresh failed: {}", t.getMessage());
            }

            // Execute PENDING advices (newest first), capped
            List<AdviceEntity> pending = adviceRepo.findAll(); // replace with your PENDING-limited query
            int executed = 0;
            for (AdviceEntity a : pending) {
                if (executed >= max) break;
                Result<?> r = adviceService.execute(a.getId());
                if (r.isOk()) executed++;
            }

            lastExecuted = executed;
            ticks.incrementAndGet();

        } catch (Throwable t) {
            lastError = t.getMessage();
            log.warn("Engine tick error", t);
        } finally {
            publishState();
        }
    }

    private void publishState() {
        try {
            stream.send("engine.state", getEngineState().get());
        } catch (Throwable ignored) {
        }
    }

    private static String safeStatus(String s) {
        if (s == null) return "—";
        String t = s.trim();
        return t.isEmpty() ? "—" : t;
    }

    public record EngineState(boolean running, Instant startedAt, Instant lastTick, long ticks, long lastExecuted,
                              String lastError, Instant asOf) {
    }


    // ---------------- Market-hours guard ----------------
    private boolean inMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

}
