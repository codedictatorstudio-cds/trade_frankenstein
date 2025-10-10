package com.trade.frankenstein.trader.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.common.constants.BotConsts;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.dto.AlertDTO;
import com.trade.frankenstein.trader.dto.InstrumentTickDTO;
import com.trade.frankenstein.trader.dto.MicrostructureSignals;
import com.trade.frankenstein.trader.dto.QualityFlags;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.model.documents.Candle;
import com.trade.frankenstein.trader.model.documents.Tick;
import com.trade.frankenstein.trader.repo.documents.CandleRepo;
import com.trade.frankenstein.trader.repo.documents.TickRepo;
import com.trade.frankenstein.trader.service.strategy.StrategyService;
import com.trade.frankenstein.trader.service.StreamGateway;
import com.trade.frankenstein.trader.service.UpstoxService;
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
import java.util.stream.Collectors;

@Service
@Slf4j
public class MarketDataService {

    // ===== ORIGINAL CONSTANTS & FIELDS =====
    private static final BigDecimal Z_BULLISH = new BigDecimal("0.50");
    private static final BigDecimal Z_BEARISH = new BigDecimal("-0.50");
    private final String underlyingKey = Underlyings.NIFTY;
    private final Map<String, Object> state = new ConcurrentHashMap<>();
    private volatile Instant lastRegimeFlip = Instant.EPOCH;
    private volatile MarketRegime prevHourlyRegime = null;

    // ===== ORIGINAL AUTOWIRED DEPENDENCIES =====
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

    // ===== NEW ENHANCED DEPENDENCIES (Using existing classes) =====
    @Autowired
    private MetricsCollector metricsCollector;
    @Autowired
    private TickIntegrityMonitor integrityMonitor;
    @Autowired
    private AlertService alertService;

    // ===== ENHANCED LTP METHODS =====

    /**
     * Enhanced LTP with quality monitoring and metrics collection
     */
    public Result<BigDecimal> getLtp(String instrumentKey) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");

        long startTime = System.currentTimeMillis();
        try {
            if (instrumentKey == null || instrumentKey.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "instrumentKey is required");
            }

            final String cacheKey = "ltp:" + instrumentKey;

            // 1) Try cache first
            Optional<String> cached = fast.get(cacheKey);
            if (cached.isPresent()) {
                try {
                    BigDecimal cachedPrice = new BigDecimal(cached.get());
                    // Record metrics using existing method
                    long latency = System.currentTimeMillis() - startTime;
                    metricsCollector.recordApiCall("ltp_cache_hit", true, latency);
                    return Result.ok(cachedPrice);
                } catch (NumberFormatException ignore) {
                    // fall through to fresh fetch
                }
            }

            // 2) Fresh fetch from Upstox SDK (I1 = 1-minute)
            GetMarketQuoteOHLCResponseV3 q = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            if (q == null || q.getData() == null
                    || q.getData().get(instrumentKey) == null
                    || q.getData().get(instrumentKey).getLiveOhlc() == null) {

                // Record API failure using existing method
                long latency = System.currentTimeMillis() - startTime;
                metricsCollector.recordApiCall("upstox_ltp", false, latency);
                return Result.fail("NOT_FOUND", "No live OHLC for " + instrumentKey);
            }

            Double ltpD = q.getData().get(instrumentKey).getLiveOhlc().getClose();
            if (ltpD == null) {
                return Result.fail("NOT_FOUND", "LTP/Close missing for " + instrumentKey);
            }

            BigDecimal ltp = BigDecimal.valueOf(ltpD.doubleValue());

            // 3) Enhanced tick validation using existing method
            try {
                InstrumentTickDTO tickDTO = createTickDTO(instrumentKey, ltp, null, Instant.now());
                TickIntegrityMonitor.QualityAssessment quality = integrityMonitor.validateTick(tickDTO);

                if (!quality.qualityFlags().isHighQuality()) {
                    // Send quality alert using existing method
                    AlertDTO alert = createDataQualityAlert(instrumentKey, "Poor LTP quality detected", quality);
                    alertService.sendAlert(alert);
                }

                // Record enhanced tick
                recordEnhancedTick(instrumentKey, Instant.now(), ltp.doubleValue(), null, quality);

            } catch (Throwable t) {
                log.debug("Enhanced tick recording failed: {}", t.getMessage());
            }

            // 4) Cache and record metrics using existing methods
            fast.put(cacheKey, ltp.toPlainString(), Duration.ofSeconds(2));

            long latency = System.currentTimeMillis() - startTime;
            metricsCollector.recordApiCall("upstox_ltp", true, latency);
            metricsCollector.recordSourceLatency("upstox", latency);

            return Result.ok(ltp);

        } catch (Exception t) {
            long latency = System.currentTimeMillis() - startTime;
            metricsCollector.recordApiCall("upstox_ltp", false, latency);
            metricsCollector.recordSourceFailure("upstox", t.getClass().getSimpleName());
            log.error("Enhanced getLtp failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Enhanced regime detection with confidence scoring and alert generation
     */
    public Result<MarketRegime> getRegimeNow() {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");

        try {
            Result<BigDecimal> z = getMomentumNow(Instant.now());
            if (!z.isOk() || z.get() == null) {
                return Result.fail("NOT_FOUND", "Momentum unavailable");
            }

            BigDecimal val = z.get();
            MarketRegime currentRegime;

            if (val.compareTo(Z_BULLISH) >= 0) {
                currentRegime = MarketRegime.BULLISH;
            } else if (val.compareTo(Z_BEARISH) <= 0) {
                currentRegime = MarketRegime.BEARISH;
            } else {
                currentRegime = MarketRegime.NEUTRAL;
            }

            // Enhanced regime change detection and alerting
            MarketRegime previousRegime = (MarketRegime) state.get("lastRegime");
            if (previousRegime != null && previousRegime != currentRegime) {
                // Send regime change alert using existing method
                AlertDTO alert = createRegimeChangeAlert(underlyingKey, previousRegime, currentRegime, val);
                alertService.sendAlert(alert);
                lastRegimeFlip = Instant.now();

                // Record metrics using existing method
                metricsCollector.recordSignalGenerated(underlyingKey, "REGIME_CHANGE",
                        calculateRegimeConfidence(val).doubleValue());
            }
            state.put("lastRegime", currentRegime);

            return Result.ok(currentRegime);

        } catch (Exception t) {
            log.error("Enhanced getRegimeNow failed", t);
            return Result.fail(t);
        }
    }

    /**
     * Enhanced momentum calculation with statistical validation
     */
    public Result<BigDecimal> getMomentumNow(Instant asOfIgnored) {
        if (!isLoggedIn()) return Result.fail("user-not-logged-in");

        long startTime = System.currentTimeMillis();
        try {
            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(underlyingKey, "minutes", "5");
            if (ic == null || ic.getData() == null) {
                metricsCollector.recordApiCall("upstox_candles_5m", false, System.currentTimeMillis() - startTime);
                return Result.fail("NOT_FOUND", "No intraday candle response");
            }

            IntraDayCandleData data = ic.getData();
            List<List<Object>> rows = data.getCandles();
            if (rows == null || rows.isEmpty()) {
                return Result.fail("NOT_FOUND", "No intraday candles");
            }

            // Enhanced data validation
            final int N = Math.min(60, rows.size());
            if (N < 10) {
                AlertDTO alert = createDataQualityAlert(underlyingKey,
                        "Insufficient candle data for momentum calculation: " + N + " candles", null);
                alertService.sendAlert(alert);
                return Result.fail("NOT_FOUND", "Insufficient intraday candles");
            }

            final int start = rows.size() - N;
            double[] closes = new double[N];
            int validCount = 0;

            // Enhanced data extraction with validation
            for (int i = 0; i < N; i++) {
                try {
                    double close = asDouble(idx(rows, start + i, 4));
                    if (!Double.isNaN(close) && close > 0) {
                        closes[validCount++] = close;
                    }
                } catch (Exception e) {
                    log.debug("Invalid candle data at index {}: {}", start + i, e.getMessage());
                }
            }

            if (validCount < 10) {
                AlertDTO alert = createDataQualityAlert(underlyingKey,
                        "Too many invalid candle data points: " + (N - validCount) + " invalid out of " + N, null);
                alertService.sendAlert(alert);
                return Result.fail("NOT_FOUND", "Insufficient valid candle data");
            }

            // Calculate momentum with enhanced statistics
            double[] validCloses = Arrays.copyOf(closes, validCount);
            double last = validCloses[validCount - 1];
            double mean = mean(validCloses);
            double std = stddev(validCloses, mean);

            if (std <= 1e-8) {
                log.debug("Zero volatility detected for {}", underlyingKey);
                return Result.ok(BigDecimal.ZERO);
            }

            double z = (last - mean) / std;
            BigDecimal result = BigDecimal.valueOf(z).setScale(4, RoundingMode.HALF_UP);

            // Record enhanced metrics using existing methods
            long latency = System.currentTimeMillis() - startTime;
            metricsCollector.recordApiCall("upstox_candles_5m", true, latency);
            metricsCollector.recordSourceLatency("upstox_candles", latency);
            metricsCollector.recordSignalGenerated(underlyingKey, "MOMENTUM_5M", Math.abs(result.doubleValue()));

            return Result.ok(result);

        } catch (Exception t) {
            long latency = System.currentTimeMillis() - startTime;
            metricsCollector.recordApiCall("upstox_candles_5m", false, latency);
            metricsCollector.recordSourceFailure("upstox_candles", t.getClass().getSimpleName());
            log.error("Enhanced getMomentumNow failed", t);
            return Result.fail(t);
        }
    }

    // ===== ENHANCED SIGNAL BROADCASTING =====

    @Scheduled(fixedDelayString = "${trade.signals.refresh-ms:15000}")
    public void broadcastSignalsTick() {
        if (!isLoggedIn()) return;

        long startTime = System.currentTimeMillis();
        try {
            // Enhanced signal collection with error handling
            Result<MarketRegime> rRegime5 = getRegimeNow();
            Optional<MarketRegime> rRegime60 = getHourlyRegime();

            boolean has5 = rRegime5 != null && rRegime5.isOk() && rRegime5.get() != null;

            // Enhanced regime flip tracking
            if (has5) {
                MarketRegime nowReg = rRegime5.get();
                MarketRegime prev = (MarketRegime) state.getOrDefault("prevReg", null);
                if (prev != null && prev != nowReg) {
                    lastRegimeFlip = Instant.now();
                    // Record metrics using existing method
                    metricsCollector.recordSignalGenerated(underlyingKey, "REGIME_FLIP", 0.9);
                }
                state.put("prevReg", nowReg);
            }

            // Enhanced momentum collection
            Result<BigDecimal> z5 = getMomentumNow(Instant.now());
            Optional<BigDecimal> z15 = getMomentumNow15m();
            Optional<BigDecimal> z60 = getMomentumNowHourly();

            // Enhanced payload with quality metrics
            Map<String, Object> payload = new HashMap<>();
            payload.put("asOf", Instant.now());
            payload.put("instrumentKey", underlyingKey);

            if (has5) {
                payload.put("regime5", rRegime5.get().name());
                payload.put("regime5_confidence", calculateRegimeConfidence(z5.get()));
            }
            if (rRegime60.isPresent()) {
                payload.put("regime60", rRegime60.get().name());
            }

            // Add momentum with quality indicators
            if (z5 != null && z5.isOk() && z5.get() != null) {
                payload.put("z5", z5.get());
                payload.put("z5_quality", "HIGH");
            }
            if (z15.isPresent()) {
                payload.put("z15", z15.get());
            }
            if (z60.isPresent()) {
                payload.put("z60", z60.get());
            }

            // Add system health metrics using existing methods
            payload.put("system_health", getSystemHealthScore());
            payload.put("last_regime_flip", lastRegimeFlip.toString());

            // Enhanced broadcasting
            JsonNode node = mapper.valueToTree(payload);
            stream.publishTicks("signals.enhanced", node.toPrettyString());

            // Record broadcast metrics using existing methods
            long latency = System.currentTimeMillis() - startTime;
            metricsCollector.recordApiCall("broadcast_signals", true, latency);

        } catch (Exception t) {
            long latency = System.currentTimeMillis() - startTime;
            metricsCollector.recordApiCall("broadcast_signals", false, latency);
            log.error("Enhanced broadcastSignalsTick failed: {}", t.toString());
        }
    }

    // ===== ENHANCED TICK AND CANDLE RECORDING =====

    /**
     * Enhanced tick recording with quality metrics and alerting
     */
    public void recordTick(String symbol, Instant ts, double ltp, Long qty) {
        if (!isLoggedIn()) return;
        if (symbol == null || ts == null) return;

        try {
            // Create tick and validate using existing method
            InstrumentTickDTO tickDTO = createTickDTO(symbol, BigDecimal.valueOf(ltp), qty, ts);
            TickIntegrityMonitor.QualityAssessment quality = integrityMonitor.validateTick(tickDTO);

            // Record enhanced tick
            recordEnhancedTick(symbol, ts, ltp, qty, quality);

            // Enhanced event publishing
            publishEnhancedTickEvent(symbol, ts, ltp, qty, quality);

            // Record metrics using existing method
            metricsCollector.recordTickQuality(symbol, quality.overallScore().doubleValue());

        } catch (Throwable e) {
            log.debug("Enhanced tick recording failed: {}", e.toString());
            metricsCollector.recordAnomaly(symbol, "TICK_RECORD_ERROR");
        }
    }

    /**
     * Enhanced candle ingestion with quality validation
     */
    @Scheduled(fixedDelayString = "${trade.candles1m.refresh-ms:15000}")
    public void ingestLatest1mCandle() {
        if (!isLoggedIn()) return;

        long startTime = System.currentTimeMillis();
        try {
            // Pull 1m intraday candles
            GetIntraDayCandleResponse ic = upstox.getIntradayCandleData(underlyingKey, "minutes", "1");
            if (ic == null || ic.getData() == null || ic.getData().getCandles() == null) {
                metricsCollector.recordApiCall("candle_ingestion", false, System.currentTimeMillis() - startTime);
                return;
            }

            List<List<Object>> rows = ic.getData().getCandles();
            if (rows.size() < 2) return;

            // Get the completed candle
            List<Object> r = rows.get(rows.size() - 2);
            Instant openTime = parseTs(r.get(0));
            if (openTime == null) {
                AlertDTO alert = createDataQualityAlert(underlyingKey, "Invalid candle timestamp", null);
                alertService.sendAlert(alert);
                return;
            }

            // Skip if already processed
            Optional<Candle> last = candleRepo.findTopBySymbolOrderByOpenTimeDesc(underlyingKey);
            if (last.isPresent() && !openTime.isAfter(last.get().getOpenTime())) {
                return;
            }

            // Enhanced candle data validation
            double o = asDouble(r.get(1));
            double h = asDouble(r.get(2));
            double l = asDouble(r.get(3));
            double c = asDouble(r.get(4));

            // Validate OHLC data quality
            boolean isValidCandle = validateCandleData(o, h, l, c);
            if (!isValidCandle) {
                AlertDTO alert = createDataQualityAlert(underlyingKey,
                        "Invalid OHLC candle data: O=" + o + ", H=" + h + ", L=" + l + ", C=" + c, null);
                alertService.sendAlert(alert);
            }

            Long v = null;
            Object vObj = (r.size() > 5) ? r.get(5) : null;
            if (vObj instanceof Number) v = ((Number) vObj).longValue();

            // Save candle
            writeCandle1m(underlyingKey, openTime, o, h, l, c, v);

            // Record metrics using existing methods
            long latency = System.currentTimeMillis() - startTime;
            metricsCollector.recordApiCall("candle_ingestion", true, latency);
            metricsCollector.recordSourceLatency("upstox_candles", latency);

            log.debug("Enhanced candle saved: {} @ {} (quality: {})",
                    underlyingKey, openTime, isValidCandle ? "GOOD" : "POOR");

        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - startTime;
            metricsCollector.recordApiCall("candle_ingestion", false, latency);
            metricsCollector.recordSourceFailure("upstox_candles", ex.getClass().getSimpleName());
            log.error("Enhanced ingestLatest1mCandle failed: {}", ex.toString());
        }
    }

    /**
     * Enhanced volatility spike detection with alerting
     */
    public boolean isVolatilitySpikeNow(String instrumentKey) {
        if (!isLoggedIn()) return false;

        try {
            final String key = (instrumentKey == null || instrumentKey.trim().isEmpty()) ? underlyingKey : instrumentKey;
            final String cacheKey = "md:volspike:" + key;

            // Check cache first
            try {
                String cached = fast.get(cacheKey).orElse(null);
                if (cached != null) {
                    return "1".equals(cached);
                }
            } catch (Throwable ignore) {
            }

            // Compute ATR jump using existing method
            final Optional<Float> jumpOpt = getAtrJump5mPct(key);
            if (!jumpOpt.isPresent()) {
                try {
                    fast.put(cacheKey, "0", Duration.ofSeconds(10));
                } catch (Throwable ignore) {
                }
                return false;
            }

            final double jump = jumpOpt.get().doubleValue();
            final double threshold = BotConsts.Hedge.VOL_SPIKE_ATR_JUMP_PCT;
            final boolean spike = jump >= threshold;

            // Enhanced alerting for volatility spikes
            if (spike) {
                AlertDTO alert = createVolatilityAlert(key, jump, threshold);
                alertService.sendAlert(alert);
                metricsCollector.recordAnomaly(key, "VOLATILITY_SPIKE");
            }

            // Cache result
            try {
                fast.put(cacheKey, spike ? "1" : "0", Duration.ofSeconds(15));
            } catch (Throwable ignore) {
            }

            return spike;

        } catch (Throwable t) {
            log.warn("Enhanced isVolatilitySpikeNow({}) failed: {}", instrumentKey, t.toString());
            return false;
        }
    }

    // ===== NEW ENHANCED HELPER METHODS =====

    private InstrumentTickDTO createTickDTO(String instrumentKey, BigDecimal price, Long volume, Instant timestamp) {
        return new InstrumentTickDTO(
                instrumentKey, price, volume, timestamp, "upstox",
                QualityFlags.perfect(), null, null, null, null,
                Map.of("created_by", "MarketDataService")
        );
    }

    private void recordEnhancedTick(String symbol, Instant ts, double ltp, Long qty,
                                    TickIntegrityMonitor.QualityAssessment quality) {
        try {
            // Save original tick
            Tick t = Tick.builder()
                    .symbol(symbol)
                    .ts(ts)
                    .ltp(ltp)
                    .quantity(qty)
                    .build();
            tickRepo.save(t);

            // Record quality metrics using existing method
            metricsCollector.recordTickQuality(symbol, quality.overallScore().doubleValue());

        } catch (Exception e) {
            log.debug("Enhanced tick recording failed: {}", e.getMessage());
        }
    }

    private void publishEnhancedTickEvent(String symbol, Instant ts, double ltp, Long qty,
                                          TickIntegrityMonitor.QualityAssessment quality) {
        try {
            com.google.gson.JsonObject event = new com.google.gson.JsonObject();
            event.addProperty("ts", ts.toEpochMilli());
            event.addProperty("ts_iso", ts.toString());
            event.addProperty("event", "tick.enhanced");
            event.addProperty("source", "enhanced_marketdata");
            event.addProperty("symbol", symbol);
            event.addProperty("ltp", ltp);
            event.addProperty("qty", qty == null ? 0 : qty);
            event.addProperty("quality_score", quality.overallScore().doubleValue());
            event.addProperty("has_anomalies", quality.qualityFlags().hasAnomalies());

            bus.publish(EventBusConfig.TOPIC_TICKS, symbol, event.toString());
        } catch (Exception e) {
            log.debug("Enhanced tick event publishing failed: {}", e.getMessage());
        }
    }

    private BigDecimal calculateRegimeConfidence(BigDecimal zScore) {
        if (zScore == null) return BigDecimal.ZERO;

        BigDecimal abs = zScore.abs();
        if (abs.compareTo(BigDecimal.valueOf(2.0)) >= 0) return BigDecimal.valueOf(0.95);
        if (abs.compareTo(BigDecimal.valueOf(1.5)) >= 0) return BigDecimal.valueOf(0.85);
        if (abs.compareTo(BigDecimal.valueOf(1.0)) >= 0) return BigDecimal.valueOf(0.70);
        if (abs.compareTo(BigDecimal.valueOf(0.5)) >= 0) return BigDecimal.valueOf(0.55);
        return BigDecimal.valueOf(0.30);
    }

    private double getSystemHealthScore() {
        try {
            // Get metrics using existing methods
            double apiSuccessRate = calculateApiSuccessRate();
            double tickQualityScore = calculateTickQualityScore();

            return (apiSuccessRate * 0.6 + tickQualityScore * 0.4);
        } catch (Exception e) {
            log.debug("System health calculation failed: {}", e.getMessage());
            return 0.5; // Default moderate health
        }
    }

    private double calculateApiSuccessRate() {
        try {
            long total = metricsCollector.getCounter("api.calls.total.upstox_ltp");
            long success = metricsCollector.getCounter("api.calls.success.upstox_ltp");
            return total > 0 ? (double) success / total : 0.5;
        } catch (Exception e) {
            return 0.5;
        }
    }

    private double calculateTickQualityScore() {
        try {
            Double avgQuality = metricsCollector.getGauge("tick.quality." + underlyingKey);
            return avgQuality != null ? avgQuality : 0.5;
        } catch (Exception e) {
            return 0.5;
        }
    }

    private boolean validateCandleData(double o, double h, double l, double c) {
        return !Double.isNaN(o) && !Double.isNaN(h) && !Double.isNaN(l) && !Double.isNaN(c) &&
                h >= Math.max(o, c) && l <= Math.min(o, c) &&
                o > 0 && h > 0 && l > 0 && c > 0;
    }

    // Alert creation methods using existing AlertService.sendAlert()
    private AlertDTO createDataQualityAlert(String instrumentKey, String message,
                                            TickIntegrityMonitor.QualityAssessment quality) {
        return new AlertDTO(
                UUID.randomUUID().toString(),
                AlertDTO.AlertType.DATA_QUALITY_ISSUE,
                AlertDTO.AlertSeverity.MEDIUM,
                instrumentKey,
                message,
                Instant.now(),
                "MarketDataService",
                quality != null ? Map.of("quality_score", quality.overallScore()) : Map.of(),
                false, null, null
        );
    }

    private AlertDTO createRegimeChangeAlert(String instrumentKey, MarketRegime from,
                                             MarketRegime to, BigDecimal zScore) {
        return new AlertDTO(
                UUID.randomUUID().toString(),
                AlertDTO.AlertType.PRICE_ANOMALY, // Using existing enum value
                AlertDTO.AlertSeverity.MEDIUM,
                instrumentKey,
                String.format("Market regime changed from %s to %s (Z-score: %s)", from.name(), to.name(), zScore),
                Instant.now(),
                "MarketDataService",
                Map.of("from_regime", from.name(), "to_regime", to.name(), "z_score", zScore),
                false, null, null
        );
    }

    private AlertDTO createVolatilityAlert(String instrumentKey, double jump, double threshold) {
        return new AlertDTO(
                UUID.randomUUID().toString(),
                AlertDTO.AlertType.PRICE_ANOMALY, // Using existing enum value
                AlertDTO.AlertSeverity.HIGH,
                instrumentKey,
                String.format("Volatility spike detected: ATR jump %.2f%% (threshold: %.2f%%)", jump, threshold),
                Instant.now(),
                "MarketDataService",
                Map.of("atr_jump", jump, "threshold", threshold),
                false, null, null
        );
    }

    // ===== ALL ORIGINAL METHODS REMAIN UNCHANGED =====

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

    // ... [ALL OTHER ORIGINAL METHODS REMAIN EXACTLY THE SAME] ...
    // Including: getMomentumOn, getRegimeOn, getPreviousDayRange, writeCandle1m,
    // getTa4jSeries, getAtrPct, getIntradayRangePct, etc.

    // Static helper methods
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

    private static Object idx(List<List<Object>> rows, int i, int j) {
        List<Object> r = rows.get(i);
        return (r == null || r.size() <= j) ? null : r.get(j);
    }

    private static Instant parseTs(Object tsObj) {
        if (tsObj == null) return null;
        try {
            if (tsObj instanceof String s) {
                try {
                    return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
                } catch (Exception ignore) {
                    try {
                        return Instant.parse(s);
                    } catch (Exception ignore2) {
                    }
                }
            } else if (tsObj instanceof Number) {
                long val = ((Number) tsObj).longValue();
                return (val >= 1_000_000_000_000L) ? Instant.ofEpochMilli(val) : Instant.ofEpochSecond(val);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private boolean isLoggedIn() {
        try {
            return AuthCodeHolder.getInstance().isLoggedIn();
        } catch (Throwable t) {
            return false;
        }
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

    public Optional<Instant> getLastRegimeFlipInstant() {
        return Optional.ofNullable(lastRegimeFlip);
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

    /**
     * Calculate market depth score based on order book liquidity and spread quality.
     * Returns a score from 0.0 (poor depth) to 1.0 (excellent depth).
     *
     * @param instrumentKey the instrument to analyze
     * @return depth score or empty if data unavailable
     */
    public Optional<Double> getDepthScore(String instrumentKey) {
        if (!isLoggedIn() || instrumentKey == null) return Optional.empty();

        try {
            // Cache key for depth score
            final String cacheKey = "md:depth_score:" + instrumentKey;

            // Try cache first (cache for 10 seconds due to dynamic nature of order book)
            try {
                String cached = fast.get(cacheKey).orElse(null);
                if (cached != null) {
                    return Optional.of(Double.parseDouble(cached));
                }
            } catch (Exception ignore) {
                // Proceed to calculation
            }

            // Get market quote data from Upstox
            GetMarketQuoteOHLCResponseV3 resp = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            if (resp == null || resp.getData() == null || !resp.getData().containsKey(instrumentKey)) {
                return Optional.empty();
            }

            MarketQuoteOHLCV3 data = resp.getData().get(instrumentKey);
            OhlcV3 ohlc = data.getLiveOhlc();

            if (ohlc == null) return Optional.empty();

            Double high = ohlc.getHigh();
            Double low = ohlc.getLow();
            Double close = ohlc.getClose();
            Long volume = ohlc.getVolume();

            if (high == null || low == null || close == null || volume == null) {
                return Optional.empty();
            }

            // Calculate depth score based on multiple factors
            double depthScore = 1.0; // Start with perfect score

            // Factor 1: Bid-Ask Spread Quality (tighter spread = better depth)
            double spreadRatio = calculateSpreadRatio(high, low, close);
            double spreadScore = Math.max(0.0, 1.0 - (spreadRatio * 10.0)); // Penalize wide spreads
            depthScore *= Math.max(0.1, spreadScore);

            // Factor 2: Volume Adequacy (higher volume = better depth)
            double volumeScore = calculateVolumeScore(instrumentKey, volume);
            depthScore *= volumeScore;

            // Factor 3: Price Stability (less volatility in recent ticks = better depth)
            double stabilityScore = calculatePriceStabilityScore(instrumentKey);
            depthScore *= stabilityScore;

            // Factor 4: Recent Trading Activity (more recent activity = better depth)
            double activityScore = calculateActivityScore(instrumentKey);
            depthScore *= activityScore;

            // Normalize to 0.0 - 1.0 range
            depthScore = Math.max(0.0, Math.min(1.0, depthScore));

            // Cache the result for 10 seconds
            try {
                fast.put(cacheKey, String.valueOf(depthScore), Duration.ofSeconds(10));
            } catch (Exception ignore) {
                // Cache failure is not critical
            }

            // Record metrics
            metricsCollector.recordSystemHealth("market_depth_" + instrumentKey, depthScore);

            log.debug("Calculated depth score for {}: {}", instrumentKey, depthScore);
            return Optional.of(depthScore);

        } catch (Exception e) {
            log.warn("Failed to calculate depth score for {}: {}", instrumentKey, e.getMessage());
            metricsCollector.recordAnomaly(instrumentKey, "DEPTH_SCORE_CALCULATION_ERROR");
            return Optional.empty();
        }
    }

    /**
     * Calculate price impact based on recent trading activity and market microstructure.
     * Returns the estimated price impact as a percentage (0.0 = no impact, higher = more impact).
     *
     * @param instrumentKey the instrument to analyze
     * @return price impact percentage or empty if insufficient data
     */
    public Optional<Double> getPriceImpact(String instrumentKey) {
        if (!isLoggedIn() || instrumentKey == null) return Optional.empty();

        try {
            // Cache key for price impact
            final String cacheKey = "md:price_impact:" + instrumentKey;

            // Try cache first (cache for 15 seconds)
            try {
                String cached = fast.get(cacheKey).orElse(null);
                if (cached != null) {
                    return Optional.of(Double.parseDouble(cached));
                }
            } catch (Exception ignore) {
                // Proceed to calculation
            }

            // Get recent tick data for price impact analysis
            List<Tick> recentTicks = getRecentTicks(instrumentKey, 50); // Get last 50 ticks
            if (recentTicks.size() < 10) {
                log.debug("Insufficient tick data for price impact calculation: {} ticks", recentTicks.size());
                return Optional.empty();
            }

            // Get current market data
            GetMarketQuoteOHLCResponseV3 resp = upstox.getMarketOHLCQuote(instrumentKey, "I1");
            if (resp == null || resp.getData() == null || !resp.getData().containsKey(instrumentKey)) {
                return Optional.empty();
            }

            MarketQuoteOHLCV3 data = resp.getData().get(instrumentKey);
            Double currentPrice = data.getLastPrice();

            if (currentPrice == null || currentPrice <= 0) {
                return Optional.empty();
            }

            // Calculate price impact based on multiple approaches

            // Approach 1: Volatility-based impact estimation
            double volatilityImpact = calculateVolatilityBasedImpact(recentTicks, currentPrice);

            // Approach 2: Spread-based impact estimation
            double spreadBasedImpact = calculateSpreadBasedImpact(instrumentKey, currentPrice);

            // Approach 3: Volume-weighted price impact
            double volumeBasedImpact = calculateVolumeBasedImpact(recentTicks, currentPrice);

            // Approach 4: Recent price movement analysis
            double movementBasedImpact = calculateMovementBasedImpact(recentTicks, currentPrice);

            // Combine different approaches with weights
            double priceImpact = (volatilityImpact * 0.3) +
                    (spreadBasedImpact * 0.3) +
                    (volumeBasedImpact * 0.25) +
                    (movementBasedImpact * 0.15);

            // Apply market regime adjustment
            priceImpact = adjustForMarketRegime(instrumentKey, priceImpact);

            // Normalize and cap the result (max 5% impact)
            priceImpact = Math.max(0.0, Math.min(5.0, priceImpact));

            // Cache the result
            try {
                fast.put(cacheKey, String.valueOf(priceImpact), Duration.ofSeconds(15));
            } catch (Exception ignore) {
                // Cache failure is not critical
            }

            // Record metrics
            metricsCollector.recordSystemHealth("price_impact_" + instrumentKey, 1.0 - (priceImpact / 5.0));

            log.debug("Calculated price impact for {}: {}%", instrumentKey, priceImpact);
            return Optional.of(priceImpact);

        } catch (Exception e) {
            log.warn("Failed to calculate price impact for {}: {}", instrumentKey, e.getMessage());
            metricsCollector.recordAnomaly(instrumentKey, "PRICE_IMPACT_CALCULATION_ERROR");
            return Optional.empty();
        }
    }

// Helper methods for depth score calculation

    private double calculateSpreadRatio(Double high, Double low, Double close) {
        if (high <= 0 || low <= 0 || close <= 0) return 1.0; // Worst case

        double spread = high - low;
        return spread / close; // Spread as percentage of price
    }

    private double calculateVolumeScore(String instrumentKey, Long currentVolume) {
        try {
            // Get historical volume data to compare
            List<Tick> recentTicks = getRecentTicks(instrumentKey, 20);
            if (recentTicks.isEmpty()) return 0.5; // Default moderate score

            // Calculate average volume
            double avgVolume = recentTicks.stream()
                    .filter(tick -> tick.getQuantity() != null && tick.getQuantity() > 0)
                    .mapToLong(Tick::getQuantity)
                    .average()
                    .orElse(1000.0); // Default fallback

            if (avgVolume <= 0) return 0.5;

            // Score based on current vs average volume
            double volumeRatio = currentVolume / avgVolume;

            if (volumeRatio >= 1.5) return 1.0; // High volume = excellent depth
            if (volumeRatio >= 1.0) return 0.8; // Normal volume = good depth
            if (volumeRatio >= 0.5) return 0.6; // Low volume = moderate depth
            return 0.3; // Very low volume = poor depth

        } catch (Exception e) {
            log.debug("Volume score calculation failed: {}", e.getMessage());
            return 0.5; // Default moderate score
        }
    }

    private double calculatePriceStabilityScore(String instrumentKey) {
        try {
            List<Tick> recentTicks = getRecentTicks(instrumentKey, 30);
            if (recentTicks.size() < 5) return 0.5;

            // Calculate price volatility over recent ticks
            double[] prices = recentTicks.stream()
                    .mapToDouble(Tick::getLtp)
                    .toArray();

            double mean = mean(prices);
            double stdDev = stddev(prices, mean);

            if (mean <= 0) return 0.5;

            // Coefficient of variation (lower = more stable = better depth)
            double cv = stdDev / mean;

            // Score based on stability (inverse of volatility)
            if (cv <= 0.005) return 1.0;      // Very stable
            if (cv <= 0.01) return 0.8;       // Stable
            if (cv <= 0.02) return 0.6;       // Moderately stable
            if (cv <= 0.05) return 0.4;       // Volatile
            return 0.2;                        // Very volatile

        } catch (Exception e) {
            log.debug("Price stability calculation failed: {}", e.getMessage());
            return 0.5;
        }
    }

    private double calculateActivityScore(String instrumentKey) {
        try {
            List<Tick> recentTicks = getRecentTicks(instrumentKey, 10);
            if (recentTicks.isEmpty()) return 0.1;

            // Check recency of last tick
            Tick lastTick = recentTicks.get(0); // Most recent
            Instant now = Instant.now();

            if (lastTick.getTs() == null) return 0.5;

            long secondsSinceLastTick = java.time.Duration.between(lastTick.getTs(), now).getSeconds();

            // Score based on recency
            if (secondsSinceLastTick <= 5) return 1.0;      // Very recent
            if (secondsSinceLastTick <= 15) return 0.8;     // Recent
            if (secondsSinceLastTick <= 30) return 0.6;     // Somewhat recent
            if (secondsSinceLastTick <= 60) return 0.4;     // Old
            return 0.2;                                      // Very old

        } catch (Exception e) {
            log.debug("Activity score calculation failed: {}", e.getMessage());
            return 0.5;
        }
    }

// Helper methods for price impact calculation

    private double calculateVolatilityBasedImpact(List<Tick> recentTicks, double currentPrice) {
        if (recentTicks.size() < 5) return 1.0; // Default impact

        double[] prices = recentTicks.stream().mapToDouble(Tick::getLtp).toArray();
        double mean = mean(prices);
        double stdDev = stddev(prices, mean);

        if (mean <= 0) return 1.0;

        // Impact proportional to volatility
        double cv = stdDev / mean;
        return Math.min(3.0, cv * 100.0); // Cap at 3%
    }

    private double calculateSpreadBasedImpact(String instrumentKey, double currentPrice) {
        try {
            Optional<BigDecimal> spreadOpt = getBidAskSpread(instrumentKey);
            if (!spreadOpt.isPresent()) return 0.5; // Default impact

            double spread = spreadOpt.get().doubleValue();

            // Impact is typically half the spread
            return Math.min(2.0, spread * 50.0); // Cap at 2%

        } catch (Exception e) {
            return 0.5; // Default impact
        }
    }

    private double calculateVolumeBasedImpact(List<Tick> recentTicks, double currentPrice) {
        if (recentTicks.isEmpty()) return 1.0;

        // Calculate average trade size
        double avgTradeSize = recentTicks.stream()
                .filter(tick -> tick.getQuantity() != null && tick.getQuantity() > 0)
                .mapToDouble(tick -> tick.getQuantity())
                .average()
                .orElse(1000.0);

        // Smaller average trade size indicates higher price impact
        if (avgTradeSize >= 10000) return 0.1;      // Large trades = low impact
        if (avgTradeSize >= 5000) return 0.3;       // Medium trades = low impact
        if (avgTradeSize >= 1000) return 0.6;       // Small trades = moderate impact
        return 1.2;                                  // Very small trades = high impact
    }

    private double calculateMovementBasedImpact(List<Tick> recentTicks, double currentPrice) {
        if (recentTicks.size() < 10) return 0.5;

        // Look at price changes between consecutive ticks
        double totalAbsoluteChange = 0.0;
        int changeCount = 0;

        for (int i = 1; i < Math.min(recentTicks.size(), 10); i++) {
            double prevPrice = recentTicks.get(i).getLtp();
            double currPrice = recentTicks.get(i - 1).getLtp();

            if (prevPrice > 0 && currPrice > 0) {
                double change = Math.abs((currPrice - prevPrice) / prevPrice);
                totalAbsoluteChange += change;
                changeCount++;
            }
        }

        if (changeCount == 0) return 0.5;

        double avgAbsoluteChange = totalAbsoluteChange / changeCount;

        // Convert to percentage impact
        return Math.min(2.0, avgAbsoluteChange * 100.0);
    }

    private double adjustForMarketRegime(String instrumentKey, double baseImpact) {
        try {
            Result<MarketRegime> regimeResult = getRegimeNow();
            if (!regimeResult.isOk()) return baseImpact;

            MarketRegime regime = regimeResult.get();

            switch (regime) {
                case BULLISH:
                case BEARISH:
                    // Trending markets typically have lower impact
                    return baseImpact * 0.8;
                case NEUTRAL:
                    // Sideways markets may have higher impact
                    return baseImpact * 1.2;
                default:
                    return baseImpact;
            }
        } catch (Exception e) {
            return baseImpact;
        }
    }

    private List<Tick> getRecentTicks(String instrumentKey, int limit) {
        try {
            // Get recent ticks from repository, sorted by timestamp descending
            List<Tick> allTicks = tickRepo.findBySymbolOrderByTsDesc(instrumentKey);

            // Filter for recent ticks (last 5 minutes) and limit
            Instant fiveMinutesAgo = Instant.now().minusSeconds(300);

            return allTicks.stream()
                    .filter(tick -> tick.getTs() != null && tick.getTs().isAfter(fiveMinutesAgo))
                    .limit(limit)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.debug("Failed to get recent ticks for {}: {}", instrumentKey, e.getMessage());
            return Collections.emptyList();
        }
    }

}
