package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.model.documents.Candle;
import com.trade.frankenstein.trader.model.documents.Tick;
import com.trade.frankenstein.trader.repo.documents.CandleRepo;
import com.trade.frankenstein.trader.repo.documents.TickRepo;
import com.upstox.api.GetIntraDayCandleResponse;
import com.upstox.api.GetMarketQuoteOHLCResponseV3;
import com.upstox.api.IntraDayCandleData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MarketDataService {

    @Autowired
    private UpstoxService upstox;                 // single Upstox entry point (SDK calls)

    @Autowired
    private StreamGateway stream;

    @Autowired
    private FastStateStore fast;                  // Redis/in-mem per toggle

    @Autowired
    private TickRepo tickRepo;

    @Autowired
    private CandleRepo candleRepo;

    /**
     * Default underlying for regime/momentum.
     */
    private final String underlyingKey = Underlyings.NIFTY;

    /**
     * Momentum → Regime thresholds.
     */
    private static final BigDecimal Z_BULLISH = new BigDecimal("0.50");
    private static final BigDecimal Z_BEARISH = new BigDecimal("-0.50");

    private volatile Instant lastRegimeFlip = Instant.EPOCH;      // hourly flip tracker
    private volatile MarketRegime prevHourlyRegime = null;

    // ---------------------------------------------------------------------
    // LTP with 2s cache (FastStateStore) + local tick persistence
    // ---------------------------------------------------------------------

    /**
     * Live LTP from Upstox (prefers liveOhlc.ltp, falls back to liveOhlc.close).
     * Also: persists a Tick locally and caches for 2s.
     * Cache key: ltp:{instrumentKey}
     */
    public Result<BigDecimal> getLtp(String instrumentKey) {
        try {
            if (instrumentKey == null || instrumentKey.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "instrumentKey is required");
            }

            final String cacheKey = "ltp:" + instrumentKey;

            // 1) Try cache first
            Optional<String> cached = fast.get(cacheKey);
            if (cached.isPresent()) {
                try {
                    return Result.ok(new BigDecimal(cached.get()));
                } catch (NumberFormatException ignore) {
                    // fall through to fresh fetch
                }
            }

            // 2) Fresh fetch from Upstox SDK (I1 = 1-minute)
            GetMarketQuoteOHLCResponseV3 q = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            if (q == null || q.getData() == null
                    || q.getData().get(instrumentKey) == null
                    || q.getData().get(instrumentKey).getLiveOhlc() == null) {
                return Result.fail("NOT_FOUND", "No live OHLC for " + instrumentKey);
            }

            Double ltpD = q.getData().get(instrumentKey).getLiveOhlc().getClose();
            if (ltpD == null) {
                // some feeds omit LTP; fallback to close
                ltpD = q.getData().get(instrumentKey).getLiveOhlc().getClose();
            }
            if (ltpD == null) {
                return Result.fail("NOT_FOUND", "LTP/Close missing for " + instrumentKey);
            }

            BigDecimal ltp = BigDecimal.valueOf(ltpD.doubleValue());

            // 3) Persist a tick locally (truthful history)
            try {
                recordTick(instrumentKey, Instant.now(), ltp.doubleValue(), null);
            } catch (Throwable t) {
                log.debug("recordTick skipped: {}", t.getMessage());
            }

            // 4) Cache for 2s
            fast.put(cacheKey, ltp.toPlainString(), Duration.ofSeconds(2));

            return Result.ok(ltp);
        } catch (Exception t) {
            log.error("getLtp failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Smarter LTP: prefer fresh local tick (<=3s old), otherwise fall back to Upstox.
     * Also records a tick when falling back to live.
     */
    public Result<BigDecimal> getLtpSmart(String instrumentKey) {
        try {
            if (instrumentKey == null || instrumentKey.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "instrumentKey is required");
            }

            // 1) Try local tick if fresh
            try {
                Optional<Tick> last = tickRepo.findTopBySymbolOrderByTsDesc(instrumentKey);
                if (last.isPresent()) {
                    Tick t = last.get();
                    if (t.getTs() != null && t.getTs().isAfter(Instant.now().minusSeconds(3))) {
                        return Result.ok(BigDecimal.valueOf(t.getLtp()));
                    }
                }
            } catch (Throwable ignore) {
                // proceed to live
            }

            // 2) Fallback to live (+ recordTick inside)
            return getLtp(instrumentKey);
        } catch (Throwable t) {
            log.error("getLtpSmart failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Latest locally-recorded LTP for a symbol, if available.
     */
    public Result<BigDecimal> getLtpLocal(String symbol) {
        try {
            return tickRepo.findTopBySymbolOrderByTsDesc(symbol)
                    .map(t -> Result.ok(BigDecimal.valueOf(t.getLtp())))
                    .orElseGet(() -> Result.fail("NOT_FOUND", "No local tick for " + symbol));
        } catch (Throwable ex) {
            return Result.fail("ERROR", "LTP local read failed: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Momentum & Regime
    // ---------------------------------------------------------------------

    /**
     * Current market regime derived from the 5-min momentum Z-score.
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
     * Live momentum Z-score from Upstox SDK intraday candles (5-minute).
     * Z = (lastClose - mean(closes[N])) / stddev(closes[N])
     */
    public Result<BigDecimal> getMomentumNow(Instant asOfIgnored) {
        try {
            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(underlyingKey, "minutes", "5");
            if (ic == null || ic.getData() == null) {
                return Result.fail("NOT_FOUND", "No intraday candle response");
            }

            IntraDayCandleData data = ic.getData();
            List<List<Object>> rows = data.getCandles();
            if (rows == null || rows.isEmpty()) {
                return Result.fail("NOT_FOUND", "No intraday candles");
            }

            // include the latest candle; require at least 10 closes for stability
            final int N = Math.min(60, rows.size());
            if (N < 10) return Result.fail("NOT_FOUND", "Insufficient intraday candles");
            final int start = rows.size() - N;

            double[] closes = new double[N];
            for (int i = 0; i < N; i++) {
                closes[i] = asDouble(idx(rows, start + i, 4)); // [ts, o, h, l, c, v, ...]
            }

            double last = closes[N - 1];
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
     * Generic Z-score on any Upstox timeframe (e.g., ("minutes","60")).
     */
    public Optional<BigDecimal> getMomentumOn(String unit, String interval) {
        try {
            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(underlyingKey, unit, interval);
            if (ic == null || ic.getData() == null) return Optional.empty();

            List<List<Object>> rows = ic.getData().getCandles();
            if (rows == null || rows.size() < 10) return Optional.empty();

            double[] closes = new double[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                closes[i] = asDouble(idx(rows, i, 4));
            }

            double last = closes[closes.length - 1];
            double m = mean(closes);
            double sd = stddev(closes, m);
            if (sd <= 1e-9) return Optional.of(BigDecimal.ZERO);

            return Optional.of(BigDecimal.valueOf((last - m) / sd).setScale(4, RoundingMode.HALF_UP));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Map Z-score to regime; track hourly regime flips for cool-downs.
     */
    public Optional<MarketRegime> getRegimeOn(String unit, String interval) {
        Optional<BigDecimal> zOpt = getMomentumOn(unit, interval);
        if (!zOpt.isPresent()) return Optional.empty();

        BigDecimal z = zOpt.get();
        MarketRegime reg = (z.compareTo(Z_BULLISH) >= 0) ? MarketRegime.BULLISH
                : (z.compareTo(Z_BEARISH) <= 0) ? MarketRegime.BEARISH
                : MarketRegime.NEUTRAL;

        if ("minutes".equalsIgnoreCase(unit) && "60".equalsIgnoreCase(interval)) {
            MarketRegime prev = prevHourlyRegime;
            if (prev != null && prev != reg) lastRegimeFlip = Instant.now();
            prevHourlyRegime = reg;
        }
        return Optional.of(reg);
    }

    public Optional<Instant> getLastRegimeFlipInstant() {
        return Optional.ofNullable(lastRegimeFlip);
    }

    // ---------------------------------------------------------------------
    // UI broadcast (unchanged API)
    // ---------------------------------------------------------------------
    private final Map<String, Object> state = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${trade.signals.refresh-ms:15000}")
    public void broadcastSignalsTick() {
        try {
            Result<MarketRegime> rRegime5 = getRegimeNow();
            Optional<MarketRegime> rRegime60 = getHourlyRegime();

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
            rRegime60.ifPresent(v -> payload.put("regime60", v.name()));
            if (z5 != null && z5.isOk() && z5.get() != null) payload.put("z5", z5.get());
            z15.ifPresent(v -> payload.put("z15", v));
            z60.ifPresent(v -> payload.put("z60", v));

            stream.send("signals.regime", payload);
        } catch (Exception t) {
            log.error("broadcastSignalsTick failed: {}", t.toString());
        }
    }

    // Convenience wrappers (unchanged API)
    public Optional<BigDecimal> getMomentumNow15m() {
        return getMomentumOn("minutes", "15");
    }

    public Optional<BigDecimal> getMomentumNowHourly() {
        return getMomentumOn("minutes", "60");
    }

    public Optional<MarketRegime> getHourlyRegime() {
        return getRegimeOn("minutes", "60");
    }

    public Optional<BigDecimal> getMomentumNow5m() {
        return getMomentumOn("minutes", "5");
    }

    /**
     * Previous Day Range from daily bars via Upstox SDK.
     * Assumes daily candle format: [ts, open, high, low, close, volume, ...]
     */
    public Optional<StrategyService.PDRange> getPreviousDayRange(String instrumentKey) {
        try {
            String key = (instrumentKey == null || instrumentKey.isEmpty()) ? Underlyings.NIFTY : instrumentKey;
            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(key, "day", "1");
            if (ic == null || ic.getData() == null) return Optional.empty();

            List<List<Object>> daily = ic.getData().getCandles();
            if (daily == null || daily.size() < 2) return Optional.empty();

            List<Object> y = daily.get(daily.size() - 2); // yesterday
            BigDecimal pdh = BigDecimal.valueOf(asDouble(y.get(2)));
            BigDecimal pdl = BigDecimal.valueOf(asDouble(y.get(3)));

            return Optional.of(new StrategyService.PDRange(pdh, pdl));
        } catch (Exception t) {
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------------
    // Helpers: robust numeric extraction from SDK arrays
    // ---------------------------------------------------------------------
    private static Object idx(List<List<Object>> rows, int i, int j) {
        List<Object> r = rows.get(i);
        return (r == null || r.size() <= j) ? null : r.get(j);
    }

    private static double asDouble(Object o) {
        if (o == null) return Double.NaN;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // ---------------------------------------------------------------------
    // Math
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

    // ---------------------------------------------------------------------
    // Local persistence helpers (ticks & 1m candles)
    // ---------------------------------------------------------------------

    /**
     * Persist a single tick for the given symbol (Mongo time-series).
     */
    public void recordTick(String symbol, Instant ts, double ltp, Long qty) {
        if (symbol == null || ts == null) return;
        Tick t = Tick.builder()
                .symbol(symbol)
                .ts(ts)
                .ltp(ltp)
                .quantity(qty)
                .build();
        tickRepo.save(t);
    }

    /**
     * Insert a 1-minute candle into candles_1m (can be changed to upsert if you add uniqueness).
     */
    public void writeCandle1m(String symbol, Instant openTime,
                              double open, double high, double low, double close, Long volume) {
        if (symbol == null || openTime == null) return;
        Candle c = Candle.builder()
                .symbol(symbol)
                .openTime(openTime)
                .openPrice(open)
                .highPrice(high)
                .lowPrice(low)
                .closePrice(close)
                .volume(volume)
                .build();
        candleRepo.save(c);
    }

    @Scheduled(fixedDelayString = "${trade.candles1m.refresh-ms:15000}")
    public void ingestLatest1mCandle() {
        try {
            // Pull 1m intraday candles for the underlying you already use
            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(underlyingKey, "minutes", "1");
            if (ic == null || ic.getData() == null || ic.getData().getCandles() == null) return;

            List<List<Object>> rows = ic.getData().getCandles();
            if (rows.size() < 2) return; // need at least one fully closed bar

            // The last element is the *building* bar; use the previous one (closed)
            List<Object> r = rows.get(rows.size() - 2); // [ts, o, h, l, c, v, ...]
            Instant openTime = parseTs(r.get(0));
            if (openTime == null) return;

            // Skip if we already have this minute
            Optional<Candle> last = candleRepo.findTopBySymbolOrderByOpenTimeDesc(underlyingKey);
            if (last.isPresent() && !openTime.isAfter(last.get().getOpenTime())) {
                return; // already recorded
            }

            double o = asDouble(r.get(1));
            double h = asDouble(r.get(2));
            double l = asDouble(r.get(3));
            double c = asDouble(r.get(4));
            Long v = null;
            Object vObj = (r.size() > 5) ? r.get(5) : null;
            if (vObj instanceof Number) v = ((Number) vObj).longValue();

            writeCandle1m(underlyingKey, openTime, o, h, l, c, v);
            log.debug("candles_1m: {} @ {} saved (o={}, h={}, l={}, c={}, v={})",
                    underlyingKey, openTime, o, h, l, c, v);
        } catch (Exception ex) {
            log.debug("ingestLatest1mCandle skipped: {}", ex.toString());
        }
    }

    private static Instant parseTs(Object tsObj) {
        if (tsObj == null) return null;
        try {
            // Upstox usually returns ISO-8601 with offset, e.g. "2025-09-21T10:15:00+05:30"
            if (tsObj instanceof String) {
                String s = (String) tsObj;
                try {
                    return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
                } catch (Exception ignore) { /* fall through */ }
                try {
                    return Instant.parse(s);
                } // works if it ends with 'Z'
                catch (Exception ignore) { /* fall through */ }
            } else if (tsObj instanceof Number) {
                long val = ((Number) tsObj).longValue();
                // Heuristic: treat ≥1e12 as millis, else seconds
                return (val >= 1_000_000_000_000L) ? Instant.ofEpochMilli(val) : Instant.ofEpochSecond(val);
            }
        } catch (Exception ignore) {
        }
        return null;
    }


    // === Get ta4j BarSeries for any Upstox timeframe (generic, used by callers) ===
    public Optional<BarSeries> getTa4jSeries(String instrumentKey, String unit, String interval) {
        try {
            String key = (instrumentKey == null || instrumentKey.trim().isEmpty()) ? underlyingKey : instrumentKey;
            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(key, unit, interval);
            if (ic == null || ic.getData() == null || ic.getData().getCandles() == null) return Optional.empty();

            List<List<Object>> rows = ic.getData().getCandles();
            Duration tf = durationOf(unit, interval);
            if (tf == null || rows.isEmpty()) return Optional.empty();

            BarSeries s = toBarSeries("UF:" + key + ":" + unit + ":" + interval, rows, tf);
            return (s == null || s.getBarCount() == 0) ? Optional.empty() : Optional.of(s);
        } catch (Throwable t) {
            log.warn("getTa4jSeries({}, {}, {}) failed: {}", instrumentKey, unit, interval, t.toString());
            return Optional.empty();
        }
    }

    // === Convert Upstox rows -> ta4j BarSeries (Java 8, ta4j 0.17-friendly) ===
    private BarSeries toBarSeries(String name, List<List<Object>> rows, Duration timeframe) {
        try {
            BarSeries series = new BaseBarSeriesBuilder().withName(name).build();
            ZoneId zone = ZoneId.of("Asia/Kolkata");
            for (List<Object> r : rows) {
                if (r == null || r.size() < 6) continue;
                long epochMs = toEpochMillis(r.get(0));
                if (epochMs <= 0L) continue;

                ZonedDateTime endZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone);
                double open = asDouble(r.get(1));
                double high = asDouble(r.get(2));
                double low = asDouble(r.get(3));
                double close = asDouble(r.get(4));
                double vol = asDouble(r.get(5));
                if (Double.isNaN(open) || Double.isNaN(high) || Double.isNaN(low) || Double.isNaN(close)) continue;
                series.addBar(new BaseBar(timeframe, endZdt, open, high, low, close, vol));
            }
            return series;
        } catch (Throwable t) {
            log.warn("toBarSeries(rows...) failed: {}", t.toString());
            return null;
        }
    }

    // === Robust parse for ts cell (Number or String; sec/ms) ===
    private long toEpochMillis(Object tsCell) {
        try {
            if (tsCell instanceof Number) {
                long v = ((Number) tsCell).longValue();
                return (v < 1_000_000_000_000L) ? v * 1000L : v;
            }
            if (tsCell != null) {
                String s = tsCell.toString().trim();
                if (s.isEmpty()) return -1L;
                long v = Long.parseLong(s);
                return (v < 1_000_000_000_000L) ? v * 1000L : v;
            }
        } catch (Throwable ignore) {
        }
        return -1L;
    }

    // === Map Upstox unit/interval to Duration ===
    private Duration durationOf(String unit, String interval) {
        if (unit == null || interval == null) return null;
        String u = unit.trim().toLowerCase();
        String iv = interval.trim().toLowerCase();
        try {
            if ("minutes".equals(u) || "minute".equals(u) || "min".equals(u))
                return Duration.ofMinutes(Long.parseLong(iv));
            if ("hour".equals(u) || "hours".equals(u) || "hr".equals(u) || "h".equals(u))
                return Duration.ofHours(Long.parseLong(iv));
            if ("day".equals(u) || "days".equals(u) || "d".equals(u)) return Duration.ofDays(Long.parseLong(iv));
        } catch (Throwable ignore) {
        }
        return null;
    }

}
