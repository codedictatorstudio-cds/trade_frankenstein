package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.upstox.api.GetIntraDayCandleResponse;
import com.upstox.api.GetMarketQuoteOHLCResponseV3;
import com.upstox.api.IntraDayCandleData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MarketDataService {

    @Autowired
    private UpstoxService upstox;

    @Autowired
    private StreamGateway stream;

    /**
     * Default underlying for market-wide decisions (used for momentum/regime).
     */
    private final String underlyingKey = Underlyings.NIFTY;

    // Momentum→Regime mapping thresholds (tweak if your signal scale changes)
    private static final BigDecimal Z_BULLISH = new BigDecimal("0.50");
    private static final BigDecimal Z_BEARISH = new BigDecimal("-0.50");

    @Autowired
    private UpstoxService upstoxService; // if not already present

    private volatile Instant lastRegimeFlip = Instant.EPOCH;      // updated when hourly regime changes
    private volatile MarketRegime prevHourlyRegime = null;        // previous hourly regime snapshot


    /**
     * Live LTP proxy from OHLC live bar's close (robust across brokers).
     */
    public Result<BigDecimal> getLtp(String instrumentKey) {
        try {
            if (instrumentKey == null || instrumentKey.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "instrumentKey is required");
            }
            GetMarketQuoteOHLCResponseV3 q = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            if (q == null || q.getData() == null || q.getData().get(instrumentKey) == null
                    || q.getData().get(instrumentKey).getLiveOhlc() == null) {
                return Result.fail("NOT_FOUND", "No live OHLC for " + instrumentKey);
            }
            double close = q.getData().get(instrumentKey).getLiveOhlc().getClose();
            return Result.ok(BigDecimal.valueOf(close));
        } catch (Exception t) {
            log.error("getLtp failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Derive the current market regime from the live momentum Z-score.
     * Uses intraday candles of the configured underlying.
     */
    public Result<MarketRegime> getRegimeNow() {
        Result<BigDecimal> z = getMomentumNow(Instant.now());
        if (!z.isOk() || z.get() == null) return Result.fail("NOT_FOUND", "Momentum unavailable");
        BigDecimal val = z.get();
        if (val.compareTo(Z_BULLISH) >= 0) return Result.ok(MarketRegime.BULLISH);
        if (val.compareTo(Z_BEARISH) <= 0) return Result.ok(MarketRegime.BEARISH);
        return Result.ok(MarketRegime.NEUTRAL);
    }

    /**
     * Live momentum Z-score for the configured underlying.
     * Implementation: Z = (lastClose - mean(closes[N])) / stddev(closes[N])
     * over the most recent lookback window of 5-minute closes.
     * <p>
     * Note: IntradayCandleResponse is array-of-arrays from Upstox; we rely on
     */
    public Result<BigDecimal> getMomentumNow(Instant asOfIgnored) {
        try {
            // Pull recent 5-minute intraday candles; adjust resolution if you prefer
            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(underlyingKey, "minutes", "5");
            if (ic == null) {
                return Result.fail("NOT_FOUND", "No intraday candle response");
            }

            // Convert array-of-arrays → typed candles (chronological order)
            IntraDayCandleData candles = ic.getData();
            if (candles == null) {
                return Result.fail("NOT_FOUND", "No intraday candles");
            }

            // If you want to avoid a possibly-partial last bar, set includeLatest = false
            final boolean includeLatest = true;
            final int endExclusive = includeLatest ? candles.getCandles().size() : Math.max(0, candles.getCandles().size() - 1);

            // Up to last 60 closes, require at least 10 for a meaningful z-score
            final int K = Math.min(60, endExclusive);
            if (K < 10) {
                return Result.fail("NOT_FOUND", "Insufficient intraday candles");
            }
            final int start = endExclusive - K;

            double[] closes = new double[K];
            for (int i = 0; i < K; i++) {
                closes[i] = (double) candles.getCandles().get(start + i).get(4);
            }

            double last = closes[K - 1];
            double mean = mean(closes);
            double std = stddev(closes, mean);
            if (std <= 1e-8) return Result.ok(BigDecimal.ZERO);

            double z = (last - mean) / std;
            return Result.ok(BigDecimal.valueOf(z).setScale(4, RoundingMode.HALF_UP));
        } catch (Exception t) {
            log.error("getMomentumNow failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Z-score of the last close vs recent closes on the given timeframe (e.g., "minutes","60").
     */
    public Optional<BigDecimal> getMomentumOn(String unit, String interval) {
        try {
            GetIntraDayCandleResponse ic = upstoxService.getIntradayCandleData(Underlyings.NIFTY, unit, interval);
            IntraDayCandleData cs = (ic == null) ? null : ic.getData();
            if (cs == null || cs.getCandles().size() < 10) return Optional.empty();

            double[] closes = cs.getCandles().stream().mapToDouble(i -> (double) i.get(4)).toArray();
            double last = closes[closes.length - 1];
            double m = mean(closes);
            double sd = stddev(closes, m);
            if (sd <= 1e-9) return Optional.of(BigDecimal.ZERO);

            BigDecimal z = BigDecimal.valueOf((last - m) / sd).setScale(4, RoundingMode.HALF_UP);
            return Optional.of(z);
        } catch (Exception t) {
            return Optional.empty();
        }
    }

    /**
     * Map momentum Z-score to a coarse regime; also tracks hourly flip instant for cooldown logic.
     */
    public Optional<MarketRegime> getRegimeOn(String unit, String interval) {
        Optional<BigDecimal> zOpt = getMomentumOn(unit, interval);
        if (zOpt.isEmpty()) return Optional.empty();

        BigDecimal z = zOpt.get();
        MarketRegime reg = (z.compareTo(Z_BULLISH) >= 0) ? MarketRegime.BULLISH
                : (z.compareTo(Z_BEARISH) <= 0) ? MarketRegime.BEARISH
                : MarketRegime.NEUTRAL;

        // Track flips on hourly regime specifically ("minutes","60")
        if ("minutes".equalsIgnoreCase(unit) && "60".equalsIgnoreCase(interval)) {
            MarketRegime prev = prevHourlyRegime;
            if (prev != null && prev != reg) {
                lastRegimeFlip = Instant.now();
            }
            prevHourlyRegime = reg;
        }
        return Optional.of(reg);
    }

    // Call this from your broadcast or whenever regimes are polled
    public Optional<Instant> getLastRegimeFlipInstant() {
        return Optional.ofNullable(lastRegimeFlip);
    }

    // ---------------------------------------------------------------------
    // Math helpers (simple, fast)
    // ---------------------------------------------------------------------
    private static double mean(double[] a) {
        double s = 0.0;
        for (double v : a) s += v;
        return a.length == 0 ? 0.0 : s / a.length;
    }

    private static double stddev(double[] a, double m) {
        if (a.length == 0) return 0.0;
        double s2 = 0.0;
        for (double v : a) {
            double d = v - m;
            s2 += d * d;
        }
        return Math.sqrt(s2 / a.length);
    }


    private final long refreshMs = 15000;

    // Add this field inside the class (e.g., MarketDataService):
    private final Map<String, Object> state = new ConcurrentHashMap<>();


    // Broadcast regime + momentum Z for the UI (no testMode / market-hours gates)
    @Scheduled(fixedDelayString = "${trade.signals.refresh-ms:15000}")
    public void broadcastSignalsTick() {
        try {
            Result<MarketRegime> rRegime5 = getRegimeNow();      // 5m-derived regime
            Optional<MarketRegime> rRegime60 = getHourlyRegime(); // hourly regime

            boolean has5 = rRegime5 != null && rRegime5.isOk() && rRegime5.get() != null;
            if (has5) {
                MarketRegime nowReg = rRegime5.get();
                MarketRegime prev = (MarketRegime) state.getOrDefault("prevReg", null);
                if (prev != null && prev != nowReg) lastRegimeFlip = Instant.now();
                state.put("prevReg", nowReg);
            }

            Result<BigDecimal> z5 = getMomentumNow(Instant.now());
            Optional<BigDecimal> z15 = getMomentumNow15m();
            Optional<BigDecimal> z60 = getMomentumNowHourly();

            Map<String, Object> payload = new HashMap<>();
            payload.put("asOf", Instant.now());
            if (has5) payload.put("regime5", rRegime5.get().name());
            rRegime60.ifPresent(marketRegime -> payload.put("regime60", marketRegime.name()));
            if (z5 != null && z5.isOk() && z5.get() != null) payload.put("z5", z5.get());
            z15.ifPresent(v -> payload.put("z15", v));
            z60.ifPresent(v -> payload.put("z60", v));

            stream.send("signals.regime", payload);
        } catch (Exception t) {
            log.error("broadcastSignalsTick failed: {}", t);
        }
    }

    // NEW: 15-minute momentum Z-score wrapper
    public Optional<BigDecimal> getMomentumNow15m() {
        return getMomentumOn("minutes", "15");
    }

    // NEW: Hourly (60-minute) momentum Z-score wrapper
    public Optional<BigDecimal> getMomentumNowHourly() {
        return getMomentumOn("minutes", "60");
    }

    // NEW: Hourly regime snapshot (tracks flips via getRegimeOn)
    public Optional<MarketRegime> getHourlyRegime() {
        return getRegimeOn("minutes", "60");
    }

    // NEW: Explicit 5-minute momentum Z-score wrapper (for clarity)
    public Optional<BigDecimal> getMomentumNow5m() {
        return getMomentumOn("minutes", "5");
    }


    /**
     * Previous Day High/Low using daily candles; requires broker API to return daily bars.
     */
    public Optional<StrategyService.PDRange> getPreviousDayRange(String instrumentKey) {
        try {
            // Try daily bars via the same candle endpoint (many brokers accept unit="day", interval="1")
            GetIntraDayCandleResponse ic = upstoxService.getIntradayCandleData(
                    (instrumentKey == null || instrumentKey.isEmpty()) ? Underlyings.NIFTY : instrumentKey,
                    "day",
                    "1"
            );
            IntraDayCandleData daily = (ic == null) ? null : ic.getData();
            if (daily == null || daily.getCandles().size() < 2) return Optional.empty();

            List<Object> y = daily.getCandles().get(daily.getCandles().size() - 2); // yesterday
            BigDecimal pdh = BigDecimal.valueOf((Long) y.get(2));
            BigDecimal pdl = BigDecimal.valueOf((Long) y.get(3));

            return Optional.of(new com.trade.frankenstein.trader.service.StrategyService.PDRange(pdh, pdl));
        } catch (Exception t) {
            return Optional.empty();
        }
    }

}
