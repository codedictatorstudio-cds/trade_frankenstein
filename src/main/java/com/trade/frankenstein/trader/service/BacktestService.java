package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.enums.FlagName;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * BacktestService (Java 8, no reflection)
 * <p>
 * Goal (Step 9 parity): Mirror live RiskService gates so backtest behaviour ≈ live:
 * - Daily loss cap
 * - Orders/minute throttle (applies to new entries only)
 * - SL cooldown window after a stop-loss
 * - 2-SL lockout (disable re-entry for remainder of the day after two SL hits)
 * <p>
 * Notes:
 * - This service does not fetch market data or compute signals.
 * You can supply a BarSeries and a SignalProvider (callback) via the overloaded run(...) method.
 * - The default run(...) keeps the public signature from earlier code; it returns a Summary with a message
 * indicating that no data-provider is wired. This preserves API while enabling new parity logic.
 * - FlagsService (if present in the context) governs whether each guard is enforced at runtime.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BacktestService {

    private final FlagsService flagsService; // no reflection; regular Spring wiring

    // ============================= Public API (kept signature) =============================

    private static double midPrice(Bar bar) {
        // Deterministic "fill" price for backtest: average of open & close
        double open = bar.getOpenPrice().doubleValue();
        double close = bar.getClosePrice().doubleValue();
        return (open + close) / 2.0;
    }

    // ============================= Primary Backtest Overload =============================

    /**
     * Legacy signature retained. This method does not perform a full backtest by itself because
     * data-loading and strategy evaluation are project-specific. It returns an informative Summary.
     */
    public Summary run(String strategyId,
                       String instrumentKey,
                       String unit,
                       String interval,
                       Instant from,
                       Instant to) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            Summary s = new Summary();
            s.setStrategyId(strategyId);
            s.setInstrumentKey(instrumentKey);
            s.setUnit(unit);
            s.setInterval(interval);
            s.setFrom(from);
            s.setTo(to);
            s.setMessage("user-not-logged-in");
            return s;
        }

        Summary s = new Summary();
        s.setStrategyId(strategyId);
        s.setInstrumentKey(instrumentKey);
        s.setUnit(unit);
        s.setInterval(interval);
        s.setFrom(from);
        s.setTo(to);
        s.setMessage("no-series-or-signal-provider-wired");
        return s;
    }

    /**
     * Backtest over a provided series and signal provider with risk-gate parity emulation.
     * This is the recommended entry-point for deterministic tests.
     *
     * @param strategyId Identifier for the strategy under test
     * @param series     Historical bars (time-ordered)
     * @param signals    Strategy callback that emits actions per bar
     * @param cfg        Risk parity configuration
     * @return Summary with basic performance & risk-block stats
     */
    public Summary run(String strategyId,
                       BarSeries series,
                       SignalProvider signals,
                       BacktestConfig cfg) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            Summary s = new Summary();
            s.setStrategyId(strategyId);
            s.setMessage("user-not-logged-in");
            return s;
        }
        if (series == null || series.isEmpty()) {
            Summary s = new Summary();
            s.setStrategyId(strategyId);
            s.setMessage("empty-series");
            return s;
        }
        if (signals == null) {
            Summary s = new Summary();
            s.setStrategyId(strategyId);
            s.setMessage("no-signal-provider");
            return s;
        }
        if (cfg == null) cfg = BacktestConfig.defaults();

        // Flags (Step 9): read feature toggles if available
        boolean guardDailyLoss = isOn(FlagName.DAILY_LOSS_GUARD, true);
        boolean guardOrdersPerMin = isOn(FlagName.MAX_ORDERS_PER_MIN_GUARD, true);
        boolean guardSlCooldown = isOn(FlagName.SL_COOLDOWN_ENABLED, true);
        boolean guardTwoSlLock = isOn(FlagName.DISABLE_REENTRY_AFTER_2_SL, true);

        RiskGateEmulator gates = new RiskGateEmulator(cfg, guardDailyLoss, guardOrdersPerMin, guardSlCooldown, guardTwoSlLock);

        // Basic position emulation
        boolean inPosition = false;
        Side posSide = Side.FLAT;
        double entryPrice = 0.0;
        double riskPerTrade = cfg.getRiskPerTradeAmount() > 0 ? cfg.getRiskPerTradeAmount() : 1.0;

        int trades = 0, wins = 0, losses = 0;
        double rrSum = 0.0;

        // Stats for blocks
        int blockedDailyLoss = 0, blockedOrdersPerMin = 0, blockedSlCooldown = 0, blockedTwoSl = 0;

        for (int i = 0; i < series.getBarCount(); i++) {
            Bar bar = series.getBar(i);
            ZonedDateTime end = bar.getEndTime();
            long nowSec = end.toEpochSecond();
            gates.rolloverIfNewDay(end.toLocalDate());

            // Exit handling first (strategy-driven)
            if (inPosition) {
                Action action = signals.onBar(i, bar, Context.open(entryPrice, posSide));
                if (action == Action.EXIT || action == Action.STOP_LOSS_HIT) {
                    double exitPrice = midPrice(bar);
                    double pnl = (posSide == Side.LONG ? (exitPrice - entryPrice) : (entryPrice - exitPrice));
                    gates.notePnl(pnl);

                    trades++;
                    if (pnl >= 0) {
                        wins++;
                        rrSum += (riskPerTrade == 0 ? 0.0 : pnl / riskPerTrade);
                    } else {
                        losses++;
                        rrSum += (riskPerTrade == 0 ? 0.0 : pnl / riskPerTrade);
                        // Inform risk emulator if loss was an SL
                        if (action == Action.STOP_LOSS_HIT) {
                            gates.noteStopLoss(nowSec);
                        }
                    }
                    inPosition = false;
                    posSide = Side.FLAT;
                    entryPrice = 0.0;
                    // continue to next bar after exit
                    continue;
                }
            }

            // Entry handling (apply risk gates)
            Action action = signals.onBar(i, bar, Context.flat());
            if ((action == Action.ENTER_LONG || action == Action.ENTER_SHORT) && !inPosition) {
                RiskGateEmulator.GateCheck gc = gates.canEnter(nowSec);
                if (gc.allowed) {
                    // Record order placement to update orders/min counters
                    gates.noteOrderPlaced(nowSec);

                    // Enter at mid-price for determinism
                    entryPrice = midPrice(bar);
                    posSide = (action == Action.ENTER_LONG) ? Side.LONG : Side.SHORT;
                    inPosition = true;
                } else {
                    // Tally block reasons
                    switch (gc.reason) {
                        case DAILY_LOSS_CAP:
                            blockedDailyLoss++;
                            break;
                        case ORDERS_PER_MIN:
                            blockedOrdersPerMin++;
                            break;
                        case SL_COOLDOWN:
                            blockedSlCooldown++;
                            break;
                        case TWO_SL_LOCK:
                            blockedTwoSl++;
                            break;
                        default:
                            break;
                    }
                }
            }
        }

        Summary out = new Summary();
        out.setStrategyId(strategyId);
        if (series.getBarCount() > 0) {
            out.setFrom(series.getBar(0).getEndTime().toInstant());
            out.setTo(series.getLastBar().getEndTime().toInstant());
        }
        out.setTrades(trades);
        out.setWins(wins);
        out.setLosses(losses);
        out.setWinRatePct(trades > 0 ? (wins * 100.0) / trades : 0.0);
        out.setAvgRR(trades > 0 ? rrSum / trades : 0.0);
        out.setBlockedByDailyLoss(blockedDailyLoss);
        out.setBlockedByOrdersPerMin(blockedOrdersPerMin);
        out.setBlockedBySlCooldown(blockedSlCooldown);
        out.setBlockedByTwoSlLock(blockedTwoSl);
        out.setMessage("ok");
        return out;
    }

    private boolean isOn(FlagName name, boolean defaultVal) {
        try {
            // FlagsService#get returns Boolean in our project
            Boolean v = flagsService == null ? null : flagsService.isOn(name);
            return v == null ? defaultVal : v.booleanValue();
        } catch (Throwable t) {
            // Defensive: never fail backtest because flags can't be read
            log.warn("Flags read failed for {}: {}", name, t.getMessage());
            return defaultVal;
        }
    }

    // ============================= Types & Helpers =============================

    public enum Action {
        HOLD,
        ENTER_LONG,
        ENTER_SHORT,
        EXIT,
        STOP_LOSS_HIT
    }

    public enum Side {FLAT, LONG, SHORT}

    public interface SignalProvider {
        /**
         * Called for each bar. Provide desired action given the current bar and context.
         * Implementation must be deterministic for test repeatability.
         */
        Action onBar(int index, Bar bar, Context ctx);
    }

    public record Context(boolean inPosition, double entryPrice, Side side) {
        public static Context flat() {
            return new Context(false, 0.0, Side.FLAT);
        }

        public static Context open(double entryPrice, Side side) {
            return new Context(true, entryPrice, side);
        }
    }

    @Data
    public static class BacktestConfig {
        private double dailyLossCap;            // absolute units (same as P&L units used by SignalProvider)
        private int maxOrdersPerMinute;         // throttle new entries/min
        private int slCooldownMinutes;          // cooldown after SL hit
        private boolean disableReentryAfter2Sl; // true => lockout after 2 SL in a day
        private double riskPerTradeAmount;      // used to compute RR

        public static BacktestConfig defaults() {
            BacktestConfig c = new BacktestConfig();
            c.dailyLossCap = 10000.0;
            c.maxOrdersPerMinute = 5;
            c.slCooldownMinutes = 5;
            c.disableReentryAfter2Sl = true;
            c.riskPerTradeAmount = 1000.0;
            return c;
        }
    }

    /**
     * Mirrors live risk gates for entries. Tracks only fast counters needed in backtests, in-memory.
     */
    static class RiskGateEmulator {

        private final BacktestConfig cfg;
        private final boolean enforceDailyLoss;
        private final boolean enforceOrdersPerMin;
        private final boolean enforceSlCooldown;
        private final boolean enforceTwoSlLock;
        private LocalDate currentDay = null;
        private double dailyPnl = 0.0;
        private int slHitsToday = 0;
        private boolean lockTwoSl = false;
        // minute -> orders count (we only need current minute; keep last bucket)
        private long currentMinuteBucket = -1L;
        private int ordersThisMinute = 0;
        // cooldown
        private long lastSlEpochSec = Long.MIN_VALUE;

        RiskGateEmulator(BacktestConfig cfg,
                         boolean enforceDailyLoss,
                         boolean enforceOrdersPerMin,
                         boolean enforceSlCooldown,
                         boolean enforceTwoSlLock) {
            this.cfg = cfg;
            this.enforceDailyLoss = enforceDailyLoss;
            this.enforceOrdersPerMin = enforceOrdersPerMin;
            this.enforceSlCooldown = enforceSlCooldown;
            this.enforceTwoSlLock = enforceTwoSlLock;
        }

        void rolloverIfNewDay(LocalDate day) {
            if (currentDay == null || !currentDay.equals(day)) {
                currentDay = day;
                dailyPnl = 0.0;
                slHitsToday = 0;
                lockTwoSl = false;
                currentMinuteBucket = -1L;
                ordersThisMinute = 0;
            }
        }

        void noteOrderPlaced(long nowSec) {
            long minBucket = nowSec / 60L;
            if (minBucket != currentMinuteBucket) {
                currentMinuteBucket = minBucket;
                ordersThisMinute = 0;
            }
            ordersThisMinute++;
        }

        void noteStopLoss(long nowSec) {
            lastSlEpochSec = nowSec;
            slHitsToday++;
            if (enforceTwoSlLock && cfg.disableReentryAfter2Sl && slHitsToday >= 2) {
                lockTwoSl = true;
            }
        }

        void notePnl(double pnl) {
            dailyPnl += pnl;
        }

        GateCheck canEnter(long nowSec) {
            // Daily loss cap
            if (enforceDailyLoss && cfg.dailyLossCap > 0 && (dailyPnl <= -Math.abs(cfg.dailyLossCap))) {
                return new GateCheck(false, BlockReason.DAILY_LOSS_CAP);
            }
            // Orders per minute throttle
            long minBucket = nowSec / 60L;
            int count = (minBucket == currentMinuteBucket) ? ordersThisMinute : 0;
            if (enforceOrdersPerMin && cfg.maxOrdersPerMinute > 0 && count >= cfg.maxOrdersPerMinute) {
                return new GateCheck(false, BlockReason.ORDERS_PER_MIN);
            }
            // SL cooldown
            if (enforceSlCooldown && cfg.slCooldownMinutes > 0 && lastSlEpochSec > 0) {
                long secsSinceSl = nowSec - lastSlEpochSec;
                if (secsSinceSl < cfg.slCooldownMinutes * 60L) {
                    return new GateCheck(false, BlockReason.SL_COOLDOWN);
                }
            }
            // Two-SL lock
            if (enforceTwoSlLock && lockTwoSl) {
                return new GateCheck(false, BlockReason.TWO_SL_LOCK);
            }
            return new GateCheck(true, BlockReason.NONE);
        }

        enum BlockReason {NONE, DAILY_LOSS_CAP, ORDERS_PER_MIN, SL_COOLDOWN, TWO_SL_LOCK}

        record GateCheck(boolean allowed, BlockReason reason) {
        }
    }

    // ============================= Output DTO =============================

    @Data
    public static class Summary {
        private String strategyId;
        private String instrumentKey;
        private String unit;
        private String interval;
        private Instant from;
        private Instant to;

        private int trades;
        private int wins;
        private int losses;
        private double winRatePct;
        private double avgRR;

        // Risk-gate block stats for parity diagnostics
        private int blockedByDailyLoss;
        private int blockedByOrdersPerMin;
        private int blockedBySlCooldown;
        private int blockedByTwoSlLock;

        private String message; // "ok" or reason
    }
}
