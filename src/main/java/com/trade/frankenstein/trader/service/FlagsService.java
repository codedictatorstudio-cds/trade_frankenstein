package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.constants.BotConsts;
import com.trade.frankenstein.trader.common.enums.FlagName;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * Computes and holds the ON/OFF state for all flags in FlagName.
 * - Uses BotConsts for thresholds/windows.
 * - Call evaluateAutoFlags(...) on every engine tick.
 * - Read via isOn(flag) inside Strategy/Risk/Orders/Decision code paths.
 */
@Component
public class FlagsService {

    private final EnumMap<FlagName, Boolean> state = new EnumMap<>(FlagName.class);

    public FlagsService() {
        // ===== Boot defaults (safe baseline; dynamic ones are recomputed on first evaluate) =====
        // Guardrails (always enforced)
        on(FlagName.DAILY_LOSS_GUARD,
                FlagName.MAX_ORDERS_PER_MIN_GUARD,
                FlagName.SL_COOLDOWN_ENABLED,
                FlagName.DISABLE_REENTRY_AFTER_2_SL,
                FlagName.TREND_FILTER_EMA_ADX,
                FlagName.MOMENTUM_CONFIRMATION,
                FlagName.OI_FILTER_ENABLED,
                FlagName.IV_SKEW_FILTER_ENABLED,
                FlagName.OPTION_CHAIN_CACHE_ENABLED,
                FlagName.SESSION_AUTO_REFRESH,
                FlagName.UPSTOX_RATE_LIMITER_ENABLED);

        // Execution prefs from defaults
        put(FlagName.USE_MARKET_ON_ENTRY, BotConsts.Exec.USE_MARKET_ON_ENTRY);
        put(FlagName.PLACE_BO_WITH_SLTP, BotConsts.Exec.PLACE_BRACKET_ORDERS);
        put(FlagName.CANCEL_REPLACE_ON_SLIPPAGE, BotConsts.Exec.CANCEL_REPLACE_SLIPPAGE_BPS > 0);
        put(FlagName.AVOID_PARTIAL_FILLS, BotConsts.Exec.AVOID_PARTIAL_FILLS_ENABLED);
        put(FlagName.AGGRESSIVE_RETRY_ENABLED, BotConsts.Exec.AGGRESSIVE_RETRY_ENABLED);

        // Data/Ops defaults
        put(FlagName.SSE_MINIMIZED, BotConsts.Sse.MINIMIZED);

        // Time-policy toggles (will be recomputed)
        off(FlagName.OPENING_5M_BLACKOUT, FlagName.LATE_ENTRY_CUTOFF, FlagName.NOON_PAUSE_WINDOW);

        // Strategy modes (default to breakout on; mean-revert decided by regime later)
        on(FlagName.BREAKOUT_MODE_ENABLED);
        off(FlagName.MEAN_REVERT_MODE_ENABLED);

        // Bias/rules (PCR/evening setups computed dynamically)
        off(FlagName.PCR_TILT, FlagName.STRANGLE_PM_PLUS1, FlagName.ATM_STRADDLE_QUIET);

        // Day biases
        off(FlagName.WEEKLY_EXPIRY_BIAS);
        put(FlagName.EXPIRY_DAY_SCALP_ONLY, BotConsts.Strategy.EXPIRY_DAY_SCALP_ONLY_ENABLED);

        // Sizing/structure
        on(FlagName.OTM_WINGS_AUTO_WIDTH);
        off(FlagName.RESTRIKE_ENABLED);

        // Hedging
        on(FlagName.DELTA_TARGET_HEDGE);
        off(FlagName.AUTO_HEDGE_ON_VOL_SPIKE);

        // Hard safeties (computed)
        off(FlagName.KILL_SWITCH_OPEN_NEW, FlagName.CIRCUIT_BREAKER_LOCKOUT);

        // Broker / mode
        off(FlagName.PAPER_TRADING_MODE);
    }

    /**
     * Main policy: recompute dynamic flags each tick. Provide cheap scalars only.
     */
    public void evaluateAutoFlags(Inputs in) {
        // --- Now/time windows ---
        final LocalTime nowT = in.now.toLocalTime();
        final LocalTime open = BotConsts.Market.OPEN;
        final LocalTime blackoutEnd = open.plusMinutes(BotConsts.Engine.OPENING_BLACKOUT_MINS);

        put(FlagName.OPENING_5M_BLACKOUT, nowT.isBefore(blackoutEnd));
        put(FlagName.LATE_ENTRY_CUTOFF, !nowT.isBefore(BotConsts.Engine.LATE_CUTOFF));
        put(FlagName.NOON_PAUSE_WINDOW,
                BotConsts.Engine.NOON_PAUSE_ENABLED
                        && !nowT.isBefore(BotConsts.Engine.NOON_PAUSE_START)
                        && nowT.isBefore(BotConsts.Engine.NOON_PAUSE_END));

        // --- Hard safeties ---
        boolean circuit = in.circuitTripped;
        boolean dailyLossHit = in.dailyLossPct >= BotConsts.Risk.MAX_DAILY_LOSS_PCT;
        put(FlagName.CIRCUIT_BREAKER_LOCKOUT, circuit);
        put(FlagName.KILL_SWITCH_OPEN_NEW, circuit || dailyLossHit);

        // --- Orders/min throttle (guard stays ON; load helps other decisions) ---
        final double ordersLoad = normalizeOrdersLoad(in.ordersPerMin, in.ordersPerMinLoad);

        // --- Strategy modes (auto) ---
        put(FlagName.BREAKOUT_MODE_ENABLED, !in.regimeQuiet || in.momentumConfirmed);
        put(FlagName.MEAN_REVERT_MODE_ENABLED, in.regimeQuiet
                && in.intradayRangePct <= BotConsts.Quiet.INTRADAY_RANGE_PCT_MAX);

        // --- PCR tilt (extremes only) ---
        boolean pcrExtreme = in.pcr != null
                && (in.pcr.doubleValue() <= BotConsts.Pcr.BULLISH_BELOW
                || in.pcr.doubleValue() >= BotConsts.Pcr.BEARISH_ABOVE);
        put(FlagName.PCR_TILT, pcrExtreme);
        put(FlagName.PCR_SMOOTHING_ENABLED, BotConsts.Pcr.SMOOTHING_ENABLED);

        // --- Quiet setups ---
        boolean vixOk = (BotConsts.Quiet.VIX_MAX <= 0.0) || (in.vix != null && in.vix.doubleValue() <= BotConsts.Quiet.VIX_MAX);
        boolean quietTape = in.atrPct <= BotConsts.Quiet.ATR_MAX_PCT
                && in.intradayRangePct <= BotConsts.Quiet.INTRADAY_RANGE_PCT_MAX;

        boolean pmPlus1Window = !nowT.isBefore(BotConsts.Engine.PM_PLUS1_START);
        boolean newsOk = !BotConsts.News.AVOID_ENABLED || !in.majorNewsSoon;
        boolean quietLoadOk = ordersLoad <= BotConsts.Quiet.ORDERS_LOAD_MAX;

        put(FlagName.STRANGLE_PM_PLUS1, pmPlus1Window && quietTape && vixOk && newsOk && quietLoadOk);

        boolean beforeCutoff = nowT.isBefore(BotConsts.Engine.LATE_CUTOFF);
        put(FlagName.ATM_STRADDLE_QUIET,
                in.regimeQuiet && quietTape && quietLoadOk && in.riskHeadroomOk && beforeCutoff);

        // --- Filters ---
        on(FlagName.TREND_FILTER_EMA_ADX);           // enforced in strategy regardless of current trend
        put(FlagName.MOMENTUM_CONFIRMATION, true);   // policy requires confirmation (actual check uses inputs)
        on(FlagName.OI_FILTER_ENABLED);
        on(FlagName.IV_SKEW_FILTER_ENABLED);
        put(FlagName.EVENT_AVOIDANCE_ENABLED, BotConsts.News.AVOID_ENABLED);

        // --- Day biases ---
        boolean isThursday = in.now.getDayOfWeek().getValue() == 4; // 1=Mon ... 7=Sun
        put(FlagName.WEEKLY_EXPIRY_BIAS, isThursday);

        // --- Sizing / Structure ---
        boolean restrikeOk = in.minutesSinceLastSl >= BotConsts.Restrike.COOL_DOWN_MINUTES
                && in.restrikesToday < BotConsts.Restrike.MAX_PER_SYMBOL_PER_DAY
                && in.trendOk
                && in.riskHeadroomOk
                && beforeCutoff;
        put(FlagName.RESTRIKE_ENABLED, restrikeOk);

        // --- Hedging ---
        put(FlagName.AUTO_HEDGE_ON_VOL_SPIKE, in.atrJump5mPct >= BotConsts.Hedge.VOL_SPIKE_ATR_JUMP_PCT);
        on(FlagName.DELTA_TARGET_HEDGE);

        // --- Execution / Broker (static from defaults + mode) ---
        put(FlagName.PAPER_TRADING_MODE, in.paperTradingMode);

        put(FlagName.USE_MARKET_ON_ENTRY, BotConsts.Exec.USE_MARKET_ON_ENTRY);
        put(FlagName.PLACE_BO_WITH_SLTP, BotConsts.Exec.PLACE_BRACKET_ORDERS);
        put(FlagName.CANCEL_REPLACE_ON_SLIPPAGE, BotConsts.Exec.CANCEL_REPLACE_SLIPPAGE_BPS > 0);
        put(FlagName.AVOID_PARTIAL_FILLS, BotConsts.Exec.AVOID_PARTIAL_FILLS_ENABLED);
        put(FlagName.AGGRESSIVE_RETRY_ENABLED, BotConsts.Exec.AGGRESSIVE_RETRY_ENABLED);
        put(FlagName.UPSTOX_RATE_LIMITER_ENABLED, BotConsts.BrokerRateLimiter.ENABLED);

        // --- Data / Ops ---
        put(FlagName.OPTION_CHAIN_CACHE_ENABLED, BotConsts.OptionChain.CACHE_ENABLED);
        put(FlagName.SESSION_AUTO_REFRESH, BotConsts.UpstoxRefresh.ENABLED);
        put(FlagName.SSE_MINIMIZED, BotConsts.Sse.MINIMIZED);
    }

    /**
     * Returns current ON/OFF for a flag.
     */
    public boolean isOn(FlagName f) {
        final Boolean v = state.get(f);
        return v != null && v;
    }

    /**
     * Snapshot view for debugging/telemetry.
     */
    public Map<FlagName, Boolean> snapshot() {
        return new EnumMap<>(state);
    }

    // ---------- helpers ----------
    private void on(FlagName... fs) {
        for (FlagName f : fs) state.put(f, true);
    }

    private void off(FlagName... fs) {
        for (FlagName f : fs) state.put(f, false);
    }

    private void put(FlagName f, boolean val) {
        state.put(f, val);
    }

    private double normalizeOrdersLoad(Integer ordersPerMin, Double load0to1) {
        if (load0to1 != null) return clamp01(load0to1);
        if (ordersPerMin == null) return 0.0;
        double load = (double) ordersPerMin / (double) BotConsts.Risk.MAX_ORDERS_PER_MIN;
        return clamp01(load);
    }

    private double clamp01(double x) {
        return x < 0 ? 0 : (x > 1 ? 1 : x);
    }

    // ---------- inputs container ----------
    public static class Inputs {
        // Clock / schedule
        public LocalDateTime now;

        // Risk
        public boolean circuitTripped;      // true if circuit breaker is tripped
        public double dailyLossPct;        // e.g., 3.2 == -3.2% loss

        // Flow / throttle
        public Integer ordersPerMin;        // raw count in the last window (optional)
        public Double ordersPerMinLoad;    // normalized 0..1 (optional)

        // SL / re-entry
        public int minutesSinceLastSl;
        public int restrikesToday;

        // Filters / signals
        public boolean trendOk;             // EMA/ADX filter green
        public boolean momentumConfirmed;   // short-term momentum confirm met
        public Double pcr;                 // Put/Call ratio (null-safe)
        public double atrPct;              // ATR as percent of instrument
        public Double vix;                 // null to ignore VIX
        public boolean majorNewsSoon;       // news in ±window
        public boolean regimeQuiet;         // derived regime
        public double intradayRangePct;    // Day's high-low % range
        public double atrJump5mPct;        // ATR% jump over last ~5m

        // Risk budget headroom
        public boolean riskHeadroomOk;

        // Mode
        public boolean paperTradingMode;
    }
}
