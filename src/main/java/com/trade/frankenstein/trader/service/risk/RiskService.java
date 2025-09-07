package com.trade.frankenstein.trader.service.risk;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.model.entity.CircuitBreakerStateEntity;
import com.trade.frankenstein.trader.model.entity.RiskLimitSnapshotEntity;
import com.trade.frankenstein.trader.model.entity.market.PositionEntity;
import com.trade.frankenstein.trader.model.entity.market.RiskConfigEntity;
import com.trade.frankenstein.trader.repo.CircuitBreakerStateRepository;
import com.trade.frankenstein.trader.repo.RiskLimitSnapshotRepository;
import com.trade.frankenstein.trader.repo.market.PositionRepository;
import com.trade.frankenstein.trader.repo.market.RiskConfigRepository;
import com.trade.frankenstein.trader.service.advice.AdviceService;
import com.trade.frankenstein.trader.service.streaming.StreamGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskService {

    // -------------------- Dependencies --------------------
    private final StreamGateway stream;
    private final RiskLimitSnapshotRepository riskSnapRepo;
    private final CircuitBreakerStateRepository circuitRepo;
    private final RiskConfigRepository riskConfigRepo;
    private final PositionRepository positionRepo;

    // -------------------- In-memory throttle (orders/minute) --------------------
    private final Deque<Instant> orderAttempts = new ConcurrentLinkedDeque<>();

    // Track last circuit state we broadcast to avoid noisy SSE
    private final AtomicReference<Boolean> lastTripped = new AtomicReference<>(null);

    // =====================================================================
    // Card data: RiskPanelCard (typed)
    // =====================================================================

    /**
     * Return the current risk snapshot and broadcast it on "risk.summary".
     * Prefers the latest RiskLimitSnapshot row; falls back to config+positions.
     */
    @Transactional(readOnly = true)
    public Result<RiskSummaryDto> getSummary() {
        RiskLimitSnapshotEntity snap = riskSnapRepo
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                .stream().findFirst().orElse(null);

        RiskSummaryDto dto = (snap != null) ? mapSnapshot(snap) : computeFromConfig();

        try {
            stream.send("risk.summary", dto);
        } catch (Throwable ignored) {
        }
        return Result.ok(dto);
    }

    private RiskSummaryDto mapSnapshot(RiskLimitSnapshotEntity s) {
        BigDecimal total = nvl(s.getRiskBudgetTotal(), BigDecimal.ZERO);
        BigDecimal used = nvl(s.getRiskBudgetUsed(), BigDecimal.ZERO);

        // budgetLeft = max(total - used, 0)
        BigDecimal left = total.subtract(used);
        if (left.signum() < 0) left = BigDecimal.ZERO;

        long lotsUsed = nzi(s.getLotsUsed());
        long lotsCap = nzi(s.getLotsCap());

        // If you prefer to reflect P&L instead:
        // int dailyLossPct = clamp0to100(nzi(s.getDayPnlPct()));
        int dailyLossPct = (total.signum() > 0)
                ? clamp0to100(used.multiply(BigDecimal.valueOf(100))
                .divide(total, 0, java.math.RoundingMode.HALF_UP).intValue())
                : 0;

        int capOpm = (int) nzi(s.getOrdersPerMinuteCap());
        int usedOpm = (int) nzi(s.getOrdersPerMinuteUsed());
        int ordersPerMinutePct = (capOpm > 0)
                ? clamp0to100((int) Math.round(usedOpm * 100.0 / capOpm))
                : 0;

        Instant asOf = nvl(s.getAsOf(), Instant.now());

        return new RiskSummaryDto(
                left,
                new LotsUsed(lotsUsed, lotsCap),
                dailyLossPct,
                ordersPerMinutePct,
                asOf
        );
    }


    /**
     * Conservative summary if no snapshot row exists:
     * - Budget: dailyLossCap from config (PnL not applied here)
     * - Lots used: derived from positions
     * - Throttle: current orders in last 60s vs cap
     */
    private RiskSummaryDto computeFromConfig() {
        RiskConfigEntity cfg = riskConfigRepo
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                .stream().findFirst().orElse(null);

        BigDecimal dailyLossCap = (cfg == null) ? BigDecimal.ZERO : nvl(cfg.getDailyLossCap(), BigDecimal.ZERO);

        // 2a) Daily loss cap enforcement via latest RiskLimitSnapshot (budget used vs total)
        try {
            RiskLimitSnapshotEntity latest = riskSnapRepo
                    .findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                    .stream().findFirst().orElse(null);
            if (latest != null) {
                java.math.BigDecimal total = nvl(latest.getRiskBudgetTotal(), java.math.BigDecimal.ZERO);
                java.math.BigDecimal used = nvl(latest.getRiskBudgetUsed(), java.math.BigDecimal.ZERO);
                if (total.signum() > 0 && used.compareTo(total) >= 0) {
                    tripCircuit("Daily loss cap reached");
                }
            } else {
                // fallback: if config has a cap but no snapshot, we cannot compute used safely here
            }
        } catch (Throwable ignored) {
        }
        long lotsCap = (cfg == null) ? 0L : nzi(cfg.getLotsCap());
        long lotsUsed = sumLotsUsed();
        int opmCap = (cfg == null) ? 0 : (int) nzi(cfg.getOrdersPerMinuteCap());
        int ordersPerMinPct = (opmCap <= 0) ? 0 : clamp0to100((int) Math.round(countOrdersLast60s() * 100.0 / opmCap));

        return new RiskSummaryDto(
                dailyLossCap,
                new LotsUsed(lotsUsed, lotsCap),
                0,
                ordersPerMinPct,
                Instant.now()
        );
    }

    // =====================================================================
    // Guardrails
    // =====================================================================

    /**
     * Validate an order draft before placement:
     * 1) Circuit breaker
     * 2) Lots cap
     * 3) Orders/minute throttle
     */
    @Transactional(readOnly = true)
    public Result<Void> checkOrder(AdviceService.OrderDraft draft) {
        if (draft == null) return Result.fail("BAD_REQUEST", "draft is required");

        // 0) Daily loss cap via latest snapshot
        RiskLimitSnapshotEntity snap = riskSnapRepo
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                .stream().findFirst().orElse(null);
        if (snap != null && snap.getRiskBudgetUsed() != null && snap.getRiskBudgetTotal() != null) {
            if (snap.getRiskBudgetUsed().compareTo(snap.getRiskBudgetTotal()) >= 0) {
                tripCircuit("Daily loss cap reached");
                try {
                    stream.send("risk.circuit", getCircuitState());
                } catch (Throwable ignored) {
                    log.info( "Failed to send risk.circuit after trip" );
                }
                return Result.fail("DAILY_LOSS_CAP", "Daily loss cap reached");
            }
        }

        // 1) Config caps
        var cfg = riskConfigRepo.findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                .stream().findFirst().orElse(null);
        long lotsCap = (cfg == null || cfg.getLotsCap() == null) ? 0L : Math.max(0L, cfg.getLotsCap());
        int opmCap = (cfg == null || cfg.getOrdersPerMinuteCap() == null) ? 0 : Math.max(0, cfg.getOrdersPerMinuteCap());

        // 2) Lots cap (predictive)
        long lotsRequested = Math.max(0, draft.getQuantity() / Math.max(1, draft.lotSize())); // or draft.lots()
        long lotsUsed = sumLotsUsed();
        if (lotsCap > 0 && (lotsUsed + lotsRequested) > lotsCap) {
            return Result.fail("LOTS_CAP",
                    String.format(Locale.ROOT, "Lots cap exceeded (%d used + %d > cap %d)", lotsUsed, lotsRequested, lotsCap));
        }

        // 3) Orders/min throttle
        int recent = countOrdersLast60s();
        if (opmCap > 0 && recent >= opmCap) {
            return Result.fail("THROTTLED", "Orders/minute throttle");
        }

        return Result.ok();
    }

    /**
     * Call this AFTER a successful placement to update the rolling throttle window.
     */
    public void noteOrderPlaced() {
        orderAttempts.addLast(Instant.now());
    }

    // =====================================================================
    // Circuit breaker
    // =====================================================================

    /**
     * Return current circuit state and broadcast "risk.circuit" if it changed.
     */
    @Transactional(readOnly = true)
    public Result<CircuitStateDto> getCircuitState() {
        CircuitStateDto state = getCircuitStateInternal();
        publishCircuitIfChanged(state);
        return Result.ok(state);
    }

    /**
     * Trip the circuit with an optional reason and broadcast "risk.circuit".
     */
    @Transactional
    public Result<Void> tripCircuit(String reason) {
        CircuitBreakerStateEntity e = new CircuitBreakerStateEntity();
        e.setActive(true);
        e.setReason((reason == null || reason.trim().isEmpty()) ? "TRIPPED" : reason.trim());
        e.setAsOf(Instant.now());

        circuitRepo.save(e);
        publishCircuitIfChanged(getCircuitStateInternal());
        return Result.ok();
    }

    /**
     * Reset the circuit and broadcast "risk.circuit".
     */
    @Transactional
    public Result<Void> resetCircuit() {
        CircuitBreakerStateEntity e = new CircuitBreakerStateEntity();
        e.setActive(false);
        e.setReason("RESET");
        e.setAsOf(Instant.now());

        circuitRepo.save(e);
        publishCircuitIfChanged(getCircuitStateInternal());
        return Result.ok();
    }

    // Internal: read current circuit row (typed)
    private CircuitStateDto getCircuitStateInternal() {
        CircuitBreakerStateEntity e = circuitRepo
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Order.desc("asOf"))))
                .stream().findFirst().orElse(null);

        boolean tripped = e != null && e.isActive(); // <-- 'active' means circuit is ON
        String reason = nzs(e != null ? e.getReason() : null);
        Instant asOf = (e != null && e.getAsOf() != null) ? e.getAsOf() : Instant.now();

        return new CircuitStateDto(tripped, reason, asOf);
    }

    private static String nzs(String s) {
        if (s == null) return "—";
        String t = s.trim();
        return t.isEmpty() ? "—" : t;
    }


    /**
     * Broadcast circuit state only if tripped flag changed since last publish.
     */
    private void publishCircuitIfChanged(CircuitStateDto state) {
        Boolean next = state.tripped();
        Boolean prev = lastTripped.getAndSet(next);
        if (prev == null || !prev.equals(next)) {
            try {
                stream.send("risk.circuit", state);
            } catch (Throwable ignored) {
            }
        }
    }

    // =====================================================================
    // Position & throttle helpers
    // =====================================================================

    /**
     * Sum absolute lots across current positions.
     */
    private long sumLotsUsed() {
        long sum = 0L;
        for (PositionEntity p : positionRepo.findAll(PageRequest.of(0, 1000)).getContent()) {
            Integer lots = p.getQuantity(); // <— EXPECTED getter (see notes below)
            sum += Math.abs(nzi(lots));
        }
        return sum;
    }

    /**
     * Count order attempts in the last 60 seconds; also prunes old entries.
     */
    private int countOrdersLast60s() {
        Instant now = Instant.now();
        while (true) {
            Instant head = orderAttempts.peekFirst();
            if (head == null || !head.isBefore(now.minusSeconds(60))) break;
            orderAttempts.pollFirst();
        }
        return orderAttempts.size();
    }

    // =====================================================================
    // Small helpers
    // =====================================================================

    private static int clamp0to100(int i) {
        return Math.max(0, Math.min(100, i));
    }

    private static <T> T nvl(T v, T d) {
        return v == null ? d : v;
    }

    private static long nzi(Number n) {
        return n == null ? 0L : Math.round(n.doubleValue());
    }

    // =====================================================================
    // DTOs (stable, UI-facing)
    // =====================================================================

    /**
     * @param riskBudgetLeft     money left for the day
     * @param lotsUsed           used/cap
     * @param dailyLossPct       0..100
     * @param ordersPerMinutePct 0..100
     */
    public record RiskSummaryDto(BigDecimal riskBudgetLeft, LotsUsed lotsUsed, int dailyLossPct, int ordersPerMinutePct,
                                 Instant asOf) {
    }

    public record LotsUsed(long used, long cap) {
    }

    public record CircuitStateDto(boolean tripped, String reason, Instant asOf) {
    }
}
