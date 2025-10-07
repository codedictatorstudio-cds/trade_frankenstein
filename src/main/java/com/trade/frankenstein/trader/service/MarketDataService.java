package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.common.constants.BotConsts;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.model.documents.Candle;
import com.trade.frankenstein.trader.model.documents.Tick;
import com.trade.frankenstein.trader.model.dto.MicrostructureSignals;
import com.trade.frankenstein.trader.repo.documents.CandleRepo;
import com.trade.frankenstein.trader.repo.documents.TickRepo;
import com.upstox.api.*;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class MarketDataService {

    // Momentum → Regime thresholds
    private static final BigDecimal Z_BULLISH = new BigDecimal("0.50");
    private static final BigDecimal Z_BEARISH = new BigDecimal("-0.50");
    // Default underlying for regime/momentum
    private final String underlyingKey = Underlyings.NIFTY;
    // ================= UI broadcast =================
    private final Map<String, Object> state = new ConcurrentHashMap<>();
    @Autowired
    private UpstoxService upstox;
    @Autowired
    private StreamGateway stream;
    @Autowired
    private FastStateStore fast;
    @Autowired
    private TickRepo tickRepo;
    @Autowired
    private CandleRepo candleRepo;
    @Autowired
    private EventPublisher bus;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private UpstoxService upstoxService;

    private volatile Instant lastRegimeFlip = Instant.EPOCH; // hourly flip tracker

    // ================= LTP with 2s cache + local tick persistence =================
    private volatile MarketRegime prevHourlyRegime = null;

    private static double stddev(double[] a, double m) {
        if (a.length == 0) return 0.0;
        double s2 = 0.0;
        for (double v : a) {
            double d = v - m;
            s2 += d * d;
        }
        return Math.sqrt(s2 / a.length);
    }

    private static double mean(double[] a) {
        double s = 0.0;
        for (double v : a) s += v;
        return a.length == 0 ? 0.0 : s / a.length;
    }

    // ================= Momentum & Regime =================

    // ================= Helpers: robust numeric extraction =================
    private static Object idx(List<List<Object>> rows, int i, int j) {
        List<Object> r = rows.get(i);
        return (r == null || r.size() <= j) ? null : r.get(j);
    }

    private static Instant parseTs(Object tsObj) {
        if (tsObj == null) return null;
        try {
            // Upstox usually returns ISO-8601 with offset, e.g. "2025-09-21T10:15:00+05:30"
            if (tsObj instanceof String s) {
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

    /**
     * Live LTP from Upstox (prefers liveOhlc.close). Persists a Tick and caches for 2s.
     * Cache key: ltp:{instrumentKey}
     */
    public Result<BigDecimal> getLtp(String instrumentKey) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
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
     */
    public Result<BigDecimal> getLtpSmart(String instrumentKey) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
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
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            return tickRepo.findTopBySymbolOrderByTsDesc(symbol)
                    .map(t -> Result.ok(BigDecimal.valueOf(t.getLtp())))
                    .orElseGet(() -> Result.fail("NOT_FOUND", "No local tick for " + symbol));
        } catch (Throwable ex) {
            return Result.fail("ERROR", "LTP local read failed: " + ex.getMessage());
        }
    }

    /**
     * Current market regime derived from the 5-min momentum Z-score.
     */
    public Result<MarketRegime> getRegimeNow() {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
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
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
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
        if (!isLoggedIn()) return Optional.empty();
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
        if (!isLoggedIn()) return Optional.empty();
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

    @Scheduled(fixedDelayString = "${trade.signals.refresh-ms:15000}")
    public void broadcastSignalsTick() {
        if (!isLoggedIn()) return;
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

            Map<String, Object> payload = new HashMap<String, Object>();
            payload.put("asOf", Instant.now());
            if (has5) payload.put("regime5", rRegime5.get().name());
            if (rRegime60.isPresent()) payload.put("regime60", rRegime60.get().name());
            if (z5 != null && z5.isOk() && z5.get() != null) payload.put("z5", z5.get());
            if (z15.isPresent()) payload.put("z15", z15.get());
            if (z60.isPresent()) payload.put("z60", z60.get());

            JsonNode node = mapper.valueToTree(payload);
            stream.publishTicks("signals.regime", node.toPrettyString());
        } catch (Exception t) {
            log.error("broadcastSignalsTick failed: {}", t.toString());
        }
    }

    // Convenience wrappers
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

    // ================= Ticks & 1m candles =================

    /**
     * Previous Day Range from daily bars via Upstox SDK.
     * Assumes daily candle format: [ts, open, high, low, close, volume, ...]
     */
    public Optional<StrategyService.PDRange> getPreviousDayRange(String instrumentKey) {
        if (!isLoggedIn()) return Optional.empty();
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

    /**
     * Persist a single tick for the given symbol (Mongo time-series).
     */
    public void recordTick(String symbol, Instant ts, double ltp, Long qty) {
        if (!isLoggedIn()) return;
        if (symbol == null || ts == null) return;
        Tick t = Tick.builder()
                .symbol(symbol)
                .ts(ts)
                .ltp(ltp)
                .quantity(qty)
                .build();
        tickRepo.save(t);
// Step-10: publish lightweight tick to Kafka (non-blocking; ignore errors)
        try {
            java.time.Instant now = java.time.Instant.now();
            String key = symbol;
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("ts", now.toEpochMilli());
            o.addProperty("ts_iso", now.toString());
            o.addProperty("event", "tick.ltp");
            o.addProperty("source", "marketdata");
            if (symbol != null) o.addProperty("symbol", symbol);
            o.addProperty("ltp", ltp);
            o.addProperty("qty", qty == null ? 0 : qty);
            bus.publish(EventBusConfig.TOPIC_TICKS, key, o.toString());
        } catch (Throwable e) {
            log.debug("tick publish skipped: {}", e.toString());
        }
    }

    /**
     * Insert a 1-minute candle into candles_1m (can be changed to upsert if you add uniqueness).
     */
    public void writeCandle1m(String symbol, Instant openTime,
                              double open, double high, double low, double close, Long volume) {
        if (!isLoggedIn()) return;
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
        if (!isLoggedIn()) return;
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

    // === Get ta4j BarSeries for any Upstox timeframe (generic, used by callers) ===
    public Optional<BarSeries> getTa4jSeries(String instrumentKey, String unit, String interval) {
        if (!isLoggedIn()) return Optional.empty();
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

    // === Auth guard ===
    private boolean isLoggedIn() {
        try {
            return AuthCodeHolder.getInstance().isLoggedIn();
        } catch (Throwable t) {
            return false;
        }
    }
    // ================= Step-9 metrics for FlagsService =================

    /**
     * ATR%% (percentage of price) over a lookback window on a given timeframe.
     * Uses Upstox intraday candles; computes classical Wilder-style ATR approximation:
     * ATR = mean(TRUE_RANGE[lookback]); pct = (ATR / lastClose) * 100.
     */
    public Optional<Float> getAtrPct(String instrumentKey, String unit, String interval, int lookback) {
        if (!isLoggedIn()) return Optional.empty();
        try {
            String key = (instrumentKey == null || instrumentKey.trim().isEmpty()) ? underlyingKey : instrumentKey;

            // Cache for a short time to cut API calls
            final String cacheKey = "md:atrpct:" + key + ":" + unit + ":" + interval + ":" + lookback;
            try {
                String cached = fast.get(cacheKey).orElse(null);
                if (cached != null) return Optional.of(Float.parseFloat(cached));
            } catch (Throwable ignore) {
            }

            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(key, unit, interval);
            if (ic == null || ic.getData() == null || ic.getData().getCandles() == null) return Optional.empty();

            List<List<Object>> cs = ic.getData().getCandles();
            final int len = cs.size();
            if (len < (lookback + 1)) return Optional.empty();

            final int HIGH = 2, LOW = 3, CLOSE = 4;
            double sumTR = 0.0;
            for (int i = len - lookback; i < len; i++) {
                List<Object> cur = cs.get(i);
                List<Object> prev = cs.get(i - 1);
                if (cur == null || prev == null || cur.size() <= CLOSE || prev.size() <= CLOSE) return Optional.empty();
                double high = asDouble(cur.get(HIGH));
                double low = asDouble(cur.get(LOW));
                double close = asDouble(cur.get(CLOSE));
                double prevClose = asDouble(prev.get(CLOSE));
                if (Double.isNaN(high) || Double.isNaN(low) || Double.isNaN(close) || Double.isNaN(prevClose))
                    return Optional.empty();

                double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
                sumTR += tr;
            }
            double atr = sumTR / lookback;
            double lastClose = asDouble(cs.get(len - 1).get(CLOSE));
            if (lastClose <= 0.0 || Double.isNaN(lastClose)) return Optional.empty();
            float pct = (float) ((atr / lastClose) * 100.0);
            float rounded = Math.round(pct * 100f) / 100f;

            try {
                fast.put(cacheKey, String.valueOf(rounded), Duration.ofSeconds(15));
            } catch (Throwable ignore) {
            }
            return Optional.of(rounded);
        } catch (Throwable t) {
            log.warn("getAtrPct({}, {}, {}, {}) failed: {}", instrumentKey, unit, interval, lookback, t.toString());
            return Optional.empty();
        }
    }

    /**
     * Intraday range %% = (HighToday - LowToday) / LastClose * 100, using intraday candles.
     * Uses "minutes/5" by default when unit/interval are null-ish.
     */
    public Optional<Float> getIntradayRangePct(String instrumentKey, String unit, String interval) {
        if (!isLoggedIn()) return Optional.empty();
        try {
            String key = (instrumentKey == null || instrumentKey.trim().isEmpty()) ? underlyingKey : instrumentKey;
            String u = (unit == null || unit.trim().isEmpty()) ? "minutes" : unit;
            String iv = (interval == null || interval.trim().isEmpty()) ? "5" : interval;

            // Cache
            final String cacheKey = "md:range:" + key + ":" + u + ":" + iv;
            try {
                String cached = fast.get(cacheKey).orElse(null);
                if (cached != null) return Optional.of(Float.parseFloat(cached));
            } catch (Throwable ignore) {
            }

            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(key, u, iv);
            if (ic == null || ic.getData() == null || ic.getData().getCandles() == null) return Optional.empty();
            List<List<Object>> rows = ic.getData().getCandles();
            if (rows.isEmpty()) return Optional.empty();

            // Determine today's date in exchange timezone
            ZoneId zone = ZoneId.of("Asia/Kolkata");
            LocalDate today = LocalDate.now(zone);

            double hi = -Double.MAX_VALUE;
            double lo = Double.MAX_VALUE;
            final int HIGH = 2, LOW = 3, CLOSE = 4;

            for (List<Object> r : rows) {
                if (r == null || r.size() <= CLOSE) continue;
                long epochMs = toEpochMillis(r.get(0));
                if (epochMs <= 0L) continue;
                LocalDate d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate();
                if (!today.equals(d)) continue; // only today

                double h = asDouble(r.get(HIGH));
                double l = asDouble(r.get(LOW));
                if (!Double.isNaN(h)) hi = Math.max(hi, h);
                if (!Double.isNaN(l)) lo = Math.min(lo, l);
            }

            if (hi == -Double.MAX_VALUE || lo == Double.MAX_VALUE || hi <= lo) return Optional.empty();

            double lastClose = asDouble(rows.get(rows.size() - 1).get(CLOSE));
            if (lastClose <= 0.0 || Double.isNaN(lastClose)) return Optional.empty();

            float pct = (float) (((hi - lo) / lastClose) * 100.0);
            float rounded = Math.round(pct * 100f) / 100f;

            try {
                fast.put(cacheKey, String.valueOf(rounded), Duration.ofSeconds(15));
            } catch (Throwable ignore) {
            }
            return Optional.of(rounded);
        } catch (Throwable t) {
            log.warn("getIntradayRangePct({}, {}, {}) failed: {}", instrumentKey, unit, interval, t.toString());
            return Optional.empty();
        }
    }

    /**
     * Short-horizon ATR jump on 5m bars:
     * Compare ATR(20) of the last 20 bars vs ATR(20) of the previous 20 bars (skipping 5 bars).
     * jump%% = ((atrNow - atrPrev) / max(atrPrev, eps)) * 100.
     */
    public Optional<Float> getAtrJump5mPct(String instrumentKey) {
        if (!isLoggedIn()) return Optional.empty();
        try {
            String key = (instrumentKey == null || instrumentKey.trim().isEmpty()) ? underlyingKey : instrumentKey;

            // Cache
            final String cacheKey = "md:atrjump5m:" + key;
            try {
                String cached = fast.get(cacheKey).orElse(null);
                if (cached != null) return Optional.of(Float.parseFloat(cached));
            } catch (Throwable ignore) {
            }

            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(key, "minutes", "5");
            if (ic == null || ic.getData() == null || ic.getData().getCandles() == null) return Optional.empty();
            List<List<Object>> cs = ic.getData().getCandles();
            final int len = cs.size();
            final int lookback = 20;
            final int skip = 5;
            final int needed = (lookback * 2) + skip + 1; // +1 for prevClose for the first window

            if (len < needed) return Optional.empty();

            final int HIGH = 2, LOW = 3, CLOSE = 4;

            // Helper to compute ATR over [start..end] inclusive where start >=1 (needs prev)
            java.util.function.BiFunction<Integer, Integer, Double> atrWindow = (startIdx, endIdx) -> {
                double sumTR = 0.0;
                for (int i = startIdx; i <= endIdx; i++) {
                    List<Object> cur = cs.get(i);
                    List<Object> prev = cs.get(i - 1);
                    double high = asDouble(cur.get(HIGH));
                    double low = asDouble(cur.get(LOW));
                    double close = asDouble(cur.get(CLOSE));
                    double prevClose = asDouble(prev.get(CLOSE));
                    if (Double.isNaN(high) || Double.isNaN(low) || Double.isNaN(close) || Double.isNaN(prevClose))
                        return Double.NaN;
                    double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
                    sumTR += tr;
                }
                return sumTR / (endIdx - startIdx + 1);
            };

            int endNow = len - 1;
            int startNow = endNow - lookback + 1;
            int endPrev = startNow - skip - 1;
            int startPrev = endPrev - lookback + 1;

            if (startPrev <= 0) return Optional.empty(); // needs previous bar at index-1

            double atrNow = atrWindow.apply(startNow, endNow);
            double atrPrev = atrWindow.apply(startPrev, endPrev);
            if (Double.isNaN(atrNow) || Double.isNaN(atrPrev)) return Optional.empty();

            double eps = 1e-8;
            float jump = (float) (((atrNow - atrPrev) / Math.max(atrPrev, eps)) * 100.0);
            float rounded = Math.round(jump * 100f) / 100f;

            try {
                fast.put(cacheKey, String.valueOf(rounded), Duration.ofSeconds(15));
            } catch (Throwable ignore) {
            }
            return Optional.of(rounded);
        } catch (Throwable t) {
            log.warn("getAtrJump5mPct({}) failed: {}", instrumentKey, t.toString());
            return Optional.empty();
        }
    }

    /**
     * Realized-volatility proxy for VIX (percent, annualized).
     * Uses 5m log-returns over the last N=60 bars (~5 hours), scaled to daily (sqrt(75)) and annualized (sqrt(252)).
     * Not a true VIX, but works as a quiet/volatile tape proxy.
     */
    public Optional<Float> getVixProxyPct(String instrumentKey) {
        if (!isLoggedIn()) return Optional.empty();
        try {
            String key = (instrumentKey == null || instrumentKey.trim().isEmpty()) ? underlyingKey : instrumentKey;

            // Cache
            final String cacheKey = "md:vixproxy5m:" + key;
            try {
                String cached = fast.get(cacheKey).orElse(null);
                if (cached != null) return Optional.of(Float.parseFloat(cached));
            } catch (Throwable ignore) {
            }

            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(key, "minutes", "5");
            if (ic == null || ic.getData() == null || ic.getData().getCandles() == null) return Optional.empty();
            List<List<Object>> rows = ic.getData().getCandles();
            final int CLOSE = 4;
            final int N = Math.min(60, rows.size());
            if (N < 30) return Optional.empty();

            double[] closes = new double[N];
            for (int i = 0; i < N; i++) {
                closes[i] = asDouble(rows.get(rows.size() - N + i).get(CLOSE));
            }

            // Compute log returns
            double[] rets = new double[N - 1];
            for (int i = 1; i < N; i++) {
                double c0 = closes[i - 1];
                double c1 = closes[i];
                if (c0 <= 0 || Double.isNaN(c0) || Double.isNaN(c1)) return Optional.empty();
                rets[i - 1] = Math.log(c1 / c0);
            }

            // stdev of returns
            double mean = mean(rets);
            double s2 = 0.0;
            for (double r : rets) {
                double d = r - mean;
                s2 += d * d;
            }
            double stdev = Math.sqrt(s2 / (rets.length <= 1 ? 1 : (rets.length - 1)));

            // Scale: 5m->daily (~75 bars), then annualize (252 days)
            double dailyVol = stdev * Math.sqrt(75.0);
            double annVol = dailyVol * Math.sqrt(252.0);
            float pct = (float) (annVol * 100.0);
            float rounded = Math.round(pct * 100f) / 100f;

            try {
                fast.put(cacheKey, String.valueOf(rounded), Duration.ofSeconds(60));
            } catch (Throwable ignore) {
            }
            return Optional.of(rounded);
        } catch (Throwable t) {
            log.warn("getVixProxyPct({}) failed: {}", instrumentKey, t.toString());
            return Optional.empty();
        }
    }


    /**
     * Volatility spike signal for AUTO_HEDGE_ON_VOL_SPIKE.
     * Logic: compute short-horizon ATR jump on 5m bars (see {@link #getAtrJump5mPct(String)})
     * and compare against BotConsts.Hedge.VOL_SPIKE_ATR_JUMP_PCT.
     * Caches the result for ~15s to reduce repeated API calls.
     *
     * @param instrumentKey underlying symbol (e.g., Underlyings.NIFTY). If null/blank, defaults to NIFTY.
     * @return true if current ATR jump %% >= configured threshold; false if data missing or below threshold.
     */
    public boolean isVolatilitySpikeNow(String instrumentKey) {
        if (!isLoggedIn()) return false;
        try {
            final String key = (instrumentKey == null || instrumentKey.trim().isEmpty()) ? underlyingKey : instrumentKey;
            final String cacheKey = "md:volspike:" + key;
            try {
                String cached = fast.get(cacheKey).orElse(null);
                if (cached != null) {
                    return "1".equals(cached);
                }
            } catch (Throwable ignore) {
            }

            // Compute ATR jump on 5m
            final java.util.Optional<Float> jumpOpt = getAtrJump5mPct(key);
            if (!jumpOpt.isPresent()) {
                // Cache negative for a short TTL to avoid hammering
                try {
                    fast.put(cacheKey, "0", java.time.Duration.ofSeconds(10));
                } catch (Throwable ignore) {
                }
                return false;
            }

            final double jump = jumpOpt.get().doubleValue();
            final double threshold = BotConsts.Hedge.VOL_SPIKE_ATR_JUMP_PCT;
            final boolean spike = jump >= threshold;

            try {
                fast.put(cacheKey, spike ? "1" : "0", java.time.Duration.ofSeconds(15));
            } catch (Throwable ignore) {
            }
            return spike;
        } catch (Throwable t) {
            log.warn("isVolatilitySpikeNow({}) failed: {}", instrumentKey, t.toString());
            return false;
        }
    }

    /**
     * Calculates the volume concentration ratio for an index based on 1-minute intraday candles.
     * Ratio = (maximum single candle volume over last N candles) / (total volume over last N candles)
     * Interpreted as: if ratio ≈ 1, highly concentrated; if low, volumes are more distributed.
     */
    public Optional<Double> getConcentrationRatio(String instrumentKey) {
        if (!isLoggedIn()) return Optional.empty();
        try {
            // Get the last N=20 1-min candles for given instrument
            int N = 20;
            GetIntraDayCandleResponse resp = upstox.getIntradayCandleData(instrumentKey, "minutes", "1");
            if (resp == null || resp.getData() == null) return Optional.empty();
            List<List<Object>> rows = resp.getData().getCandles();
            if (rows == null || rows.size() < N)
                return Optional.empty();

            // Get last N (or all, if fewer)
            List<List<Object>> lastN = rows.subList(Math.max(0, rows.size() - N), rows.size());
            double maxVolume = 0, totalVolume = 0;
            for (List<Object> r : lastN) {
                if (r.size() < 6) continue;
                Object vObj = r.get(5);
                double vol = 0;
                if (vObj instanceof Number) vol = ((Number) vObj).doubleValue();
                else try {
                    vol = Double.parseDouble(String.valueOf(vObj));
                } catch (Exception e) {
                    vol = 0;
                }
                if (vol > maxVolume) maxVolume = vol;
                totalVolume += vol;
            }
            if (totalVolume <= 0) return Optional.empty();
            return Optional.of(maxVolume / totalVolume);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<MicrostructureSignals> getMicrostructure(String instrumentKey) {
        if (!isLoggedIn() || instrumentKey == null) return Optional.empty();
        try {
            GetMarketQuoteOHLCResponseV3 resp = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            if (resp == null || resp.getData() == null || !resp.getData().containsKey(instrumentKey))
                return Optional.empty();

            MarketQuoteOHLCV3 data = resp.getData().get(instrumentKey);
            OhlcV3 ohlc = data.getLiveOhlc(); // Use live_ohlc field
            if (ohlc == null) return Optional.empty();

            // Use provided fields
            Double high = ohlc.getHigh();
            Double low = ohlc.getLow();
            Double open = ohlc.getOpen();
            Double close = ohlc.getClose();
            Long volume = ohlc.getVolume();
            Long ts = ohlc.getTs();

            Double lastPrice = data.getLastPrice();
            String instrumentToken = data.getInstrumentToken();

            // Calculate Bid-Ask Spread (use high-low over mid)
            double vHigh = high != null ? high : 0.0;
            double vLow = low != null ? low : 0.0;
            double mid = (vHigh + vLow) / 2.0;
            BigDecimal bidAskSpread = mid > 0 ? BigDecimal.valueOf((vHigh - vLow) / mid).abs() : BigDecimal.ZERO;

            // Order book imbalance (not present in your fields, so omit or use other proxy)

            BigDecimal imbalance = BigDecimal.ZERO; // placeholder (add logic if you have bid/ask)

            // Trade size skew — if using volume
            BigDecimal tradeSizeSkew = volume != null ? BigDecimal.valueOf(volume) : BigDecimal.ZERO;

            double depthScore = 1.0; // placeholder
            double priceImpact = 0.0; // placeholder

            MicrostructureSignals signals = new MicrostructureSignals(
                    bidAskSpread,
                    imbalance,
                    tradeSizeSkew,
                    depthScore,
                    priceImpact,
                    ts != null ? Instant.ofEpochMilli(ts) : Instant.now()
            );
            return Optional.of(signals);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<BigDecimal> getBidAskSpread(String instrumentKey) {
        try {
            GetMarketQuoteOHLCResponseV3 resp = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            if (resp == null || resp.getData() == null || !resp.getData().containsKey(instrumentKey))
                return Optional.empty();
            OhlcV3 ohlc = resp.getData().get(instrumentKey).getLiveOhlc();
            if (ohlc == null || ohlc.getHigh() == null || ohlc.getLow() == null) return Optional.empty();
            double mid = (ohlc.getHigh() + ohlc.getLow()) / 2.0;
            return mid != 0.0
                    ? Optional.of(BigDecimal.valueOf(Math.abs((ohlc.getHigh() - ohlc.getLow()) / mid)))
                    : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<BigDecimal> getOrderBookImbalance(String instrumentKey) {
        try {
            GetMarketQuoteOHLCResponseV3 resp = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            if (resp == null || resp.getData() == null || !resp.getData().containsKey(instrumentKey))
                return Optional.empty();
            MarketQuoteOHLCV3 data = resp.getData().get(instrumentKey);
            OhlcV3 prev = data.getPrevOhlc();
            OhlcV3 live = data.getLiveOhlc();

            if (prev == null || prev.getClose() == null || live == null || live.getClose() == null)
                return Optional.empty();

            // Use price change as a proxy for imbalance:
            double prevClose = prev.getClose();
            double liveClose = live.getClose();

            if (prevClose == 0) return Optional.empty();
            BigDecimal pseudoImbalance = BigDecimal.valueOf((liveClose - prevClose) / prevClose);

            return Optional.of(pseudoImbalance);
        } catch (Exception e) {
            return Optional.empty();
        }
    }


    public Optional<BigDecimal> getTradeSizeSkew(String instrumentKey) {
        try {
            // If you have tick-level trade size, average and compare, else use volume field from 1-min candle
            GetMarketQuoteOHLCResponseV3 resp = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            if (resp == null || resp.getData() == null || !resp.getData().containsKey(instrumentKey))
                return Optional.empty();
            OhlcV3 ohlc = resp.getData().get(instrumentKey).getLiveOhlc();
            if (ohlc == null || ohlc.getVolume() == null) return Optional.empty();
            return Optional.of(BigDecimal.valueOf(ohlc.getVolume()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<Double> getDepthScore(String instrumentKey) {
        // If you have more granular market depth info (e.g. multiple levels), use it. Placeholder = 1.0
        return Optional.of(1.0);
    }

    public Optional<Double> getPriceImpact(String instrumentKey) {
        // Needs tick data: For now, as placeholder, return 0.0.
        return Optional.of(0.0);
    }

    /**
     * Returns current daily volatility (percent) for a symbol using business data source.
     *
     * @param symbol asset symbol (e.g., "RELIANCE", "NIFTY")
     * @param days   lookback period
     */
    public Optional<BigDecimal> getCurrentVolatility(String symbol, int days) {
        try {
            List<BigDecimal> closes = getRecentClosePrices(symbol, days + 1);
            if (closes == null || closes.size() < 2) return Optional.empty();

            double[] logReturns = new double[closes.size() - 1];
            for (int i = 1; i < closes.size(); i++) {
                double prev = closes.get(i - 1).doubleValue();
                double curr = closes.get(i).doubleValue();
                if (prev <= 0 || curr <= 0) return Optional.empty();
                logReturns[i - 1] = Math.log(curr / prev);
            }

            double mean = 0.0;
            for (double r : logReturns) mean += r;
            mean /= logReturns.length;

            double variance = 0.0;
            for (double r : logReturns) variance += Math.pow(r - mean, 2);
            variance /= logReturns.length;

            double stdDev = Math.sqrt(variance);
            double volPct = stdDev * 100.0; // percent
            return Optional.of(BigDecimal.valueOf(volPct));
        } catch (Exception e) {
            log.error("Volatility calculation failed for symbol {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    public List<BigDecimal> getRecentClosePrices(String symbol, int days) {
        try {
            if (symbol == null || symbol.trim().isEmpty()) {
                throw new RuntimeException("Symbol cannot be null or empty");
            }

            if (days <= 0) {
                throw new RuntimeException("Days must be positive");
            }

            // Use the existing UpstoxService to fetch historical candle data
            // Format dates for Upstox API (DD-MM-YYYY format)
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
            LocalDate fromDate = today.minusDays(days + 5); // Extra buffer for weekends/holidays

            String toDateStr = today.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            String fromDateStr = fromDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

            // Fetch historical daily candles using the existing upstoxService
            GetHistoricalCandleResponse response = upstoxService.getHistoricalCandleData(
                    symbol, "day", "1", toDateStr, fromDateStr);

            if (response == null || response.getData() == null || response.getData().getCandles() == null) {
                log.warn("No historical candle data available for symbol: {}", symbol);
                return new ArrayList<>();
            }

            List<List<Object>> candles = response.getData().getCandles();
            List<BigDecimal> closePrices = new ArrayList<>();

            // Extract close prices from candles
            // Candle format: [timestamp, open, high, low, close, volume]
            for (List<Object> candle : candles) {
                if (candle != null && candle.size() >= 5) {
                    Object closeObj = candle.get(4); // Close price is at index 4
                    double closePrice = asDouble(closeObj);
                    if (!Double.isNaN(closePrice) && closePrice > 0) {
                        closePrices.add(BigDecimal.valueOf(closePrice));
                    }
                }
            }

            // Sort by timestamp (oldest first) - Upstox may return in different order
            // We need to sort the candles by timestamp first, then extract closes
            candles.sort((a, b) -> {
                long timestampA = toEpochMillis(a.get(0));
                long timestampB = toEpochMillis(b.get(0));
                return Long.compare(timestampA, timestampB);
            });

            // Re-extract closes in correct chronological order
            closePrices.clear();
            for (List<Object> candle : candles) {
                if (candle != null && candle.size() >= 5) {
                    Object closeObj = candle.get(4);
                    double closePrice = asDouble(closeObj);
                    if (!Double.isNaN(closePrice) && closePrice > 0) {
                        closePrices.add(BigDecimal.valueOf(closePrice).setScale(2, RoundingMode.HALF_UP));
                    }
                }
            }

            // Ensure we have enough data points
            if (closePrices.size() < days) {
                log.warn("Insufficient historical data for {}: got {} days, requested {} days",
                        symbol, closePrices.size(), days);
            }

            // Return the most recent 'days' number of closes
            int startIndex = Math.max(0, closePrices.size() - days);
            List<BigDecimal> result = closePrices.subList(startIndex, closePrices.size());

            log.debug("Fetched {} close prices for symbol {} (requested {} days)",
                    result.size(), symbol, days);

            return new ArrayList<>(result); // Return defensive copy

        } catch (Exception e) {
            log.error("Failed to fetch recent close prices for symbol {}: {}", symbol, e.getMessage());
            throw new RuntimeException("Failed to fetch historical prices for " + symbol, e);
        }
    }

    // Helper method to safely convert Object to double (already exists in your class)
    private static double asDouble(Object o) {
        if (o == null) return Double.NaN;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

}
