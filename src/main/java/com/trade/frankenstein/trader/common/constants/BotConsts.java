package com.trade.frankenstein.trader.common.constants;

import java.time.LocalTime;

/**
 * BotConsts — single interface holding all default thresholds, windows, and feature-related constants.
 * Notes:
 * - Percentages are "human percent" (e.g., 3.0 == 3%).
 * - Times are IST defaults; adjust if needed.
 */
public interface BotConsts {

    // ====== Market schedule / windows ======
    interface Market {
        LocalTime OPEN = LocalTime.of(9, 15);
    }

    interface Engine {
        int OPENING_BLACKOUT_MINS = 5;
        LocalTime PM_PLUS1_START = LocalTime.of(15, 1);
        LocalTime LATE_CUTOFF = LocalTime.of(15, 20);

        boolean NOON_PAUSE_ENABLED = false;
        LocalTime NOON_PAUSE_START = LocalTime.of(12, 0);
        LocalTime NOON_PAUSE_END = LocalTime.of(12, 15);
    }

    // ====== Core risk limits ======
    interface Risk {
        double MAX_DAILY_LOSS_PCT = 3.0;
        int MAX_ORDERS_PER_MIN = 6;
        int ORDERS_PER_MIN_WINDOW_SEC = 60;
        int SL_COOLDOWN_MINUTES = 7;
        int MAX_RESTRIKES_PER_SYMBOL_PER_DAY = 1;
        double MIN_HEADROOM_BUDGET_PCT = 20.0;
        boolean CIRCUIT_AUTO_RESET_AT_SOD = true;
    }

    // ====== PCR tilt ======
    interface Pcr {
        double BULLISH_BELOW = 0.80;
        double BEARISH_ABOVE = 1.20;
        int TILT_SCORE_POINTS = 6;
        boolean SMOOTHING_ENABLED = true;
        int SMOOTHING_EMA_SPAN_MIN = 5;
    }

    // ====== Quiet regime qualifiers ======
    interface Quiet {
        double ATR_MAX_PCT = 0.45;
        double INTRADAY_RANGE_PCT_MAX = 0.60;
        double ORDERS_LOAD_MAX = 0.60; // 0..1
        double VIX_MAX = 14.0; // 0 to ignore VIX
    }

    // ====== News / event avoidance ======
    interface News {
        boolean AVOID_ENABLED = true;
        int LOOKAHEAD_MIN = 30;
        int LOOKBEHIND_MIN = 30;
    }

    // ====== Trend / momentum filters ======
    interface Trend {
        int EMA_SHORT = 8;
        int EMA_LONG = 21;
        int ADX_MIN = 18;
    }

    interface Momentum {
        int CONFIRM_PERIOD_MIN = 5;
    }

    // ====== Strategy structure & sizing ======
    interface Strategy {
        double STRANGLE_WINGDIST_ATR_MULT = 1.2;
        int LOTS_BASE = 1;
        int LOTS_QUIET_CAP = 2;
        int WEEKLY_EXPIRY_BIAS_LOTS = 1;
        boolean EXPIRY_DAY_SCALP_ONLY_ENABLED = false;
    }

    // ====== Re-strike policy ======
    interface Restrike {
        int COOL_DOWN_MINUTES = 7;
        int MAX_PER_SYMBOL_PER_DAY = 1;
    }

    // ====== Hedging ======
    interface Hedge {
        int DELTA_TARGET_ABS = 5;
        int DELTA_CHECK_INTERVAL_SEC = 60;
        double VOL_SPIKE_ATR_JUMP_PCT = 0.20;
        int VOL_SPIKE_MAX_HEDGE_LOTS = 1;
    }

    // ====== Execution & broker ======
    interface Exec {
        boolean USE_MARKET_ON_ENTRY = true;
        boolean PLACE_BRACKET_ORDERS = false;
        int CANCEL_REPLACE_SLIPPAGE_BPS = 8;
        boolean AGGRESSIVE_RETRY_ENABLED = false;
        int AGGRESSIVE_RETRY_MAX_ATTEMPTS = 3;
        boolean AVOID_PARTIAL_FILLS_ENABLED = false;
        int AVOID_PARTIAL_FILLS_WAIT_MS = 1500;
    }

    interface BrokerRateLimiter {
        boolean ENABLED = true;
        int QPS = 8;
        int BURST = 16;
    }

    // ====== Data / option chain filters ======
    interface OptionChain {
        boolean CACHE_ENABLED = true;
        int CACHE_TTL_SECONDS = 10;
        int OI_MIN = 50000;
        double IV_SKEW_MAX_PCT = 12.0;
    }

    // ====== Session / token refresh ======
    interface UpstoxRefresh {
        boolean ENABLED = true;
        long BEFORE_MS = 300000L; // 5 minutes
        long PERIOD_MS = 60000L;  // 1 minute
    }

    // ====== Advice autorun & UI stream ======
    interface Advice {
        boolean AUTORUN_ENABLED = false;
        int AUTORUN_MIN_CONFIDENCE = 70; // 0..100
    }

    interface Sse {
        boolean MINIMIZED = false;
    }
}
