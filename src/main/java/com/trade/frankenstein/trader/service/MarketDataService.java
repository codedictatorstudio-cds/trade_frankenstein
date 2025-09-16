package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.model.upstox.IntradayCandleResponse;
import com.trade.frankenstein.trader.model.upstox.OHLC_Quotes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private String underlyingKey = "NSE_INDEX|Nifty 50";

    // Momentum→Regime mapping thresholds (tweak if your signal scale changes)
    private static final BigDecimal Z_BULLISH = new BigDecimal("0.50");
    private static final BigDecimal Z_BEARISH = new BigDecimal("-0.50");

    /**
     * Live LTP proxy from OHLC live bar's close (robust across brokers).
     */
    public Result<BigDecimal> getLtp(String instrumentKey) {
        try {
            if (instrumentKey == null || instrumentKey.trim().isEmpty()) {
                return Result.fail("BAD_REQUEST", "instrumentKey is required");
            }
            OHLC_Quotes q = upstox.getMarketOHLCQuote(instrumentKey, "1minute");
            if (q == null || q.getData() == null || q.getData().get(instrumentKey) == null
                    || q.getData().get(instrumentKey).getLive_ohlc() == null) {
                return Result.fail("NOT_FOUND", "No live OHLC for " + instrumentKey);
            }
            double close = q.getData().get(instrumentKey).getLive_ohlc().getClose();
            return Result.ok(BigDecimal.valueOf(close));
        } catch (Throwable t) {
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
     * {@link IntradayCandleResponse#toCandleList()} to convert it to typed candles.
     */
    public Result<BigDecimal> getMomentumNow(Instant asOfIgnored) {
        try {
            // Pull recent 5-minute intraday candles; adjust resolution if you prefer
            IntradayCandleResponse ic = upstox.getIntradayCandleData(underlyingKey, "minutes", "5");
            if (ic == null) {
                return Result.fail("NOT_FOUND", "No intraday candle response");
            }

            // Convert array-of-arrays → typed candles (chronological order)
            List<IntradayCandleResponse.Candle> candles = ic.toCandleList();
            if (candles == null || candles.isEmpty()) {
                return Result.fail("NOT_FOUND", "No intraday candles");
            }

            // If you want to avoid a possibly-partial last bar, set includeLatest = false
            final boolean includeLatest = true;
            final int endExclusive = includeLatest ? candles.size() : Math.max(0, candles.size() - 1);

            // Up to last 60 closes, require at least 10 for a meaningful z-score
            final int K = Math.min(60, endExclusive);
            if (K < 10) {
                return Result.fail("NOT_FOUND", "Insufficient intraday candles");
            }
            final int start = endExclusive - K;

            double[] closes = new double[K];
            for (int i = 0; i < K; i++) {
                closes[i] = candles.get(start + i).getClose();
            }

            double last = closes[K - 1];
            double mean = mean(closes);
            double std = stddev(closes, mean);
            if (std <= 1e-8) return Result.ok(BigDecimal.ZERO);

            double z = (last - mean) / std;
            return Result.ok(BigDecimal.valueOf(z).setScale(4, RoundingMode.HALF_UP));
        } catch (Throwable t) {
            log.error("getMomentumNow failed", t);
            return Result.fail(t);
        }
    }

    // ---------------------------------------------------------------------
    // Math helpers (simple, fast)
    // ---------------------------------------------------------------------
    private static double mean(double[] a) {
        double s = 0.0;
        for (double v : a) s += v;
        return s / a.length;
    }

    private static double stddev(double[] a, double mean) {
        double s2 = 0.0;
        for (double v : a) {
            double d = v - mean;
            s2 += d * d;
        }
        return Math.sqrt(s2 / Math.max(1, a.length - 1));
    }

    @Value("${trade.signals.refresh-ms:15000}")
    private long refreshMs;

    // Broadcast regime + momentum Z for the UI (no testMode / market-hours gates)
    @Scheduled(fixedDelayString = "${trade.signals.refresh-ms:15000}")
    public void broadcastSignalsTick() {
        try {
            Result<MarketRegime> rRegime = getRegimeNow();
            Result<BigDecimal> rZ = getMomentumNow(Instant.now());

            Map<String, Object> payload = new HashMap<String, Object>();
            payload.put("asOf", Instant.now());
            if (rRegime != null && rRegime.isOk() && rRegime.get() != null) {
                payload.put("regime", rRegime.get().name());
            }
            if (rZ != null && rZ.isOk() && rZ.get() != null) {
                payload.put("z", rZ.get()); // BigDecimal
            }

            stream.send("signals.regime", payload);
        } catch (Throwable t) {
            log.debug("broadcastSignalsTick failed: {}", t.getMessage());
        }
    }
}
