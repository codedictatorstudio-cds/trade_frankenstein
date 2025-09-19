package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.common.constants.RiskConstants;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.model.documents.DecisionQuality;
import com.trade.frankenstein.trader.model.documents.MarketSentimentSnapshot;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.model.upstox.IntradayCandleResponse;
import com.trade.frankenstein.trader.model.upstox.OHLC_Quotes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DecisionService {

    @Autowired
    private SentimentService sentimentService;
    @Autowired
    private MarketDataService marketDataService;
    @Autowired
    private OptionChainService optionChainService;
    @Autowired
    private StreamGateway streamGateway;
    @Autowired
    private RiskService riskService;
    @Autowired
    private UpstoxService upstoxService;

    /**
     * Upstox underlying instrument_key for NIFTY index (used by OptionChainService and OHLC endpoints).
     * Example defaults:
     * - Index key: "NSE_INDEX|Nifty 50"
     * - Derivatives root: "NFO:NIFTY50-INDEX"
     */
    private final String niftyUnderlyingKey = Underlyings.NIFTY;

    // at top of DecisionService
    private static final long FRESH_SENTIMENT_SEC = 120;
    private static final long FRESH_CANDLES_SEC = 300;     // 5m window for 5m bars
    private static final long FRESH_OHLC_SEC = 90;

    private volatile Integer lastEmittedScore = null;
    private volatile String lastEmittedTrend = null;
    private volatile String lastConfidenceBucket = null;

    public Result<DecisionQuality> getQuality() {
        try {
            final Instant now = Instant.now();

            // ----- Sentiment -----
            int sScore = 50;
            Instant sAsOf = null;
            try {
                Result<MarketSentimentSnapshot> sRes = sentimentService.getNow();
                if (sRes != null && sRes.isOk() && sRes.get() != null) {
                    MarketSentimentSnapshot snap = sRes.get();
                    sAsOf = snap.getAsOf();
                    Integer sc = snap.getScore();
                    sScore = clamp0to100(sc == null ? 50 : sc);
                } else {
                    log.error("getQuality(): sentiment fallback used");
                }
            } catch (Exception t) {
                log.error("getQuality(): sentiment unavailable: {}", t);
            }

            // ----- Regime -----
            MarketRegime regimeNow = null;
            int regimeScore = 50;
            try {
                Result<MarketRegime> r = marketDataService.getRegimeNow(); // your existing method
                if (r != null && r.isOk()) {
                    regimeNow = r.get();
                    regimeScore = normalizeRegimeScore(regimeNow);
                }
            } catch (Exception t) {
                log.error("getQuality(): regime unavailable: {}", t);
            }

            // ----- Momentum z-score (5m closes) -----
            BigDecimal momZ = BigDecimal.ZERO;
            int momScore = 50;
            try {
                Result<BigDecimal> m = marketDataService.getMomentumNow(now);
                if (m != null && m.isOk() && m.get() != null) {
                    momZ = m.get();
                    momScore = clamp0to100((int) Math.round(50 + momZ.doubleValue() * 20)); // ~[-2,2]→0..100
                }
            } catch (Exception t) {
                log.error("getQuality(): momentum unavailable: {}", t);
            }

            // ----- Structure (ADX) for adaptive weights -----
            double adx14 = 0.0;
            try {
                IntradayCandleResponse c5 = upstoxService.getIntradayCandleData(niftyUnderlyingKey, "minutes", "5");
                adx14 = computeAdx14(c5); // helper below (safe on short history)
            } catch (Exception t) {
                log.error("getQuality(): ADX unavailable: {}", t);
            }

            // ----- Adaptive weights -----
            Weights w = chooseWeights(adx14);

            // Base score
            double raw = (w.ws * sScore) + (w.wr * regimeScore) + (w.wm * momScore);

            // PCR nudge (nearest expiry)
            try {
                Result<List<LocalDate>> expsRes = optionChainService.listNearestExpiries(niftyUnderlyingKey, 1);
                if (expsRes != null && expsRes.isOk() && expsRes.get() != null && !expsRes.get().isEmpty()) {
                    LocalDate expiry = expsRes.get().get(0);
                    Result<BigDecimal> pcrRes = optionChainService.getOiPcr(niftyUnderlyingKey, expiry);
                    if (pcrRes != null && pcrRes.isOk() && pcrRes.get() != null) {
                        BigDecimal pcr = pcrRes.get();
                        if (pcr.compareTo(new BigDecimal("1.20")) > 0) raw += 5;   // CE tilt
                        else if (pcr.compareTo(new BigDecimal("0.80")) < 0) raw -= 5; // PE tilt
                    }
                }
            } catch (Exception t) {
                log.error("getQuality(): PCR context unavailable: {}", t);
            }

            // ----- Risk: circuit breaker → cap & neutralize -----
            boolean circuitTripped = false;
            try {
                Result<RiskSnapshot> r = riskService.getSummary();
                if (r != null && r.isOk() && r.get() != null) {
                    circuitTripped = isCircuitLikeTripped(r.get());
                }
            } catch (Exception ignored) {
                // optional: also try a dedicated circuit endpoint if your RiskService exposes it
                // try { circuitTripped = riskService.getCircuitState().isOk() && Boolean.TRUE.equals(riskService.getCircuitState().get().isTripped()); } catch (Exception ignore2) {}
            }

            // ----- Confidence (freshness + availability) -----
            int confidence = 100;
            if (sAsOf != null && Duration.between(sAsOf, now).getSeconds() > FRESH_SENTIMENT_SEC) confidence -= 20;
            if (adx14 <= 0.0) confidence -= 10; // missing structure
            if (momZ == null) confidence -= 10; // missing momentum
            confidence = clamp0to100(confidence);

            // Final trend
            String trend = (regimeNow == null ? "NEUTRAL" : regimeNow.name());

            // Risk cap
            int score = clamp0to100((int) Math.round(raw));
            if (circuitTripped) {
                trend = "NEUTRAL";
                score = Math.min(score, 40); // don’t claim high quality under a hard stop
            }

            // Smooth & de-noise emit
            int smoothScore = smoothScore(score);
            String confBucket = (confidence >= 80 ? "HIGH" : (confidence >= 60 ? "MED" : "LOW"));

            // ----- Reasons & tags -----
            List<String> reasons = new ArrayList<>();
            reasons.add("Sentiment " + sScore);
            reasons.add("Regime " + trend);
            reasons.add("Momentum z≈" + momZ);
            reasons.add("ADX " + String.format("%.1f", adx14));
            if (circuitTripped) reasons.add("Circuit TRIPPED");

            Map<String, String> tags = computeTags(niftyUnderlyingKey, regimeNow, sScore, momZ);
            tags.put("Confidence", String.valueOf(confidence));

            DecisionQuality dto = new DecisionQuality(smoothScore, trend, reasons, tags, now);

            if (shouldEmit(smoothScore, trend, confBucket)) {
                streamGateway.send("decision.quality", dto);
                lastEmittedScore = smoothScore;
                lastEmittedTrend = trend;
                lastConfidenceBucket = confBucket;
                log.info("decision.quality -> score={}, trend={}, conf={}, adx={}", smoothScore, trend, confBucket, String.format("%.1f", adx14));
            }

            return Result.ok(dto);
        } catch (Exception t) {
            log.error("getQuality() failed", t);
            return Result.fail(t);
        }
    }

    private boolean isCircuitLikeTripped(RiskSnapshot rs) {
        if (rs == null) return false;

        // 1) Out of budget or fully exhausted daily loss
        if (rs.getRiskBudgetLeft() <= 0.0) return true;
        if (rs.getDailyLossPct() >= 100.0) return true;

        // 2) Lots guardrail breached
        Integer used = rs.getLotsUsed();
        Integer cap = rs.getLotsCap();
        if (used != null && cap != null && cap > 0 && used >= cap) return true;

        // 3) Order rate limiter blown
        if (rs.getOrdersPerMinPct() >= 100.0) return true;

        return false;
    }

    // -------- helpers --------

    private static int clamp0to100(int v) {
        return Math.max(0, Math.min(100, v));
    }

    // Java 8-friendly switch
    private static int normalizeRegimeScore(MarketRegime r) {
        if (r == null) return 50;
        switch (r) {
            case BULLISH:
                return 70;
            case BEARISH:
                return 30;
            case RANGE_BOUND:
                return 50;
            case HIGH_VOLATILITY:
                return 45;
            case LOW_VOLATILITY:
                return 55;
            case NEUTRAL:
            default:
                return 50;
        }
    }

    private Map<String, String> computeTags(String underlyingKey,
                                            MarketRegime regimeNow,
                                            int sentimentScore,
                                            BigDecimal momZ) {

        Map<String, String> map = new HashMap<String, String>();

        // ---- 1) RR tag (ATR-based) ----
        String rrTag = "RR:OK";
        try {
            // last 20 × 5-minute candles
            IntradayCandleResponse ic =
                    upstoxService.getIntradayCandleData(underlyingKey, "minutes", "5");

            double atr = computeAtr(ic, 20);   // absolute points
            double last = lastClose(ic);       // last close
            double atrPct = (last > 0.0) ? (atr / last) * 100.0 : 0.0;

            double stopMult = 1.00;                  // ≈ 1×ATR baseline
            if (atrPct <= 0.30) stopMult = 0.80;     // very quiet → tighter stop
            else if (atrPct >= 1.00) stopMult = 1.20;// very volatile → wider stop

            double targetMult = 1.50;                // neutral target ≈ 1.5R

            // Bias target with context
            if (regimeNow == MarketRegime.BULLISH || regimeNow == MarketRegime.BEARISH) targetMult += 0.25;
            if (sentimentScore >= 60) targetMult += 0.25;
            if (sentimentScore <= 40) targetMult -= 0.25;

            double z = (momZ == null) ? 0.0 : momZ.doubleValue();
            if (z >= 0.8) targetMult += 0.25;
            if (z <= -0.8) targetMult -= 0.25;

            // Keep sane bounds
            stopMult = Math.max(0.60, Math.min(1.40, stopMult));
            targetMult = Math.max(1.00, Math.min(3.00, targetMult));

            double rr = targetMult / stopMult;
            rrTag = (rr >= 2.0) ? "GOOD" : (rr >= 1.5 ? "OK" : "POOR");
        } catch (Exception ignored) {
            // keep default
        }
        map.put("RR", rrTag);

        // ---- 2) Slippage tag (1-min live bar roughness vs MAX_SLIPPAGE_PCT) ----
        String slipTag = "LOW";
        try {
            OHLC_Quotes q = upstoxService.getMarketOHLCQuote(underlyingKey, "I1");
            if (q != null && q.getData() != null) {
                OHLC_Quotes.OHLCData d = q.getData().get(underlyingKey);
                if (d != null && d.getLive_ohlc() != null) {
                    OHLC_Quotes.Ohlc o = d.getLive_ohlc();
                    double high = o.getHigh();
                    double low = o.getLow();
                    double mid = (high + low) / 2.0;
                    if (mid > 0 && high >= low) {
                        double liveRangePct = ((high - low) / mid) * 100.0;

                        double maxSlip = RiskConstants.MAX_SLIPPAGE_PCT.doubleValue();
                        if (liveRangePct > maxSlip) slipTag = "HIGH";
                        else if (liveRangePct > 0.5 * maxSlip) slipTag = "MED";
                        else slipTag = "LOW";
                    }
                }
            }
        } catch (Exception ignored) { /* keep default */ }
        map.put("Slippage", slipTag);

        // ---- 3) Throttle tag (orders/min load from RiskSnapshot) ----
        String thrTag = "NORMAL";
        try {
            Result<RiskSnapshot> r = riskService.getSummary();
            if (r != null && r.isOk() && r.get() != null) {
                Double pct = r.get().getOrdersPerMinPct();
                double p = (pct == null) ? 0.0 : pct;
                if (p >= 100.0) thrTag = "BLOCKED";
                else if (p >= 60.0) thrTag = "HOT";
                else thrTag = "NORMAL";
            }
        } catch (Exception ignored) {
            log.error("computeTags(): throttle tag unavailable: {}", ignored);
        }
        map.put("Throttle", thrTag);

        return map;
    }

    // -------- IntradayCandleResponse helpers (array-of-arrays → typed list) --------

    private static List<IntradayCandleResponse.Candle> candles(IntradayCandleResponse ic) {
        if (ic == null) return java.util.Collections.<IntradayCandleResponse.Candle>emptyList();
        try {
            // Uses the converter added to IntradayCandleResponse
            List<IntradayCandleResponse.Candle> list = ic.toCandleList();
            return (list == null) ? java.util.Collections.<IntradayCandleResponse.Candle>emptyList() : list;
        } catch (Exception t) {
            log.error("candles() parse failed: {}", t);
            return java.util.Collections.<IntradayCandleResponse.Candle>emptyList();
        }
    }

    private static double lastClose(IntradayCandleResponse ic) {
        List<IntradayCandleResponse.Candle> cs = candles(ic);
        return cs.isEmpty() ? 0.0 : cs.get(cs.size() - 1).getClose();
    }

    /**
     * Compute ATR (simple TR average) over last {@code n} candles.
     * Expects candles in chronological order.
     */
    private static double computeAtr(IntradayCandleResponse ic, int n) {
        List<IntradayCandleResponse.Candle> cs = candles(ic);
        int len = cs.size();
        if (len < 2) return 0.0;

        // Use at most 'n' periods, but need a previous close → max (len - 1)
        int lookback = Math.min(n, len - 1);

        double sumTR = 0.0;
        // start where (i - 1) is valid; include exactly 'lookback' TR values
        for (int i = len - lookback; i < len; i++) {
            IntradayCandleResponse.Candle cur = cs.get(i);
            IntradayCandleResponse.Candle prev = cs.get(i - 1);

            double highLow = cur.getHigh() - cur.getLow();
            double highPrev = Math.abs(cur.getHigh() - prev.getClose());
            double lowPrev = Math.abs(cur.getLow() - prev.getClose());

            double tr = Math.max(highLow, Math.max(highPrev, lowPrev));
            if (tr > 0.0) sumTR += tr;
        }
        return sumTR / lookback; // Simple moving average of TR
    }

    private static final double ADX_TREND_MIN = 20.0;

    private static final class Weights {
        final double ws, wr, wm;

        Weights(double ws, double wr, double wm) {
            this.ws = ws;
            this.wr = wr;
            this.wm = wm;
        }
    }

    private Weights chooseWeights(double adx14) {
        if (adx14 >= ADX_TREND_MIN) {
            return new Weights(0.20, 0.40, 0.40); // trend: lean on regime+momentum
        } else {
            return new Weights(0.50, 0.25, 0.25); // chop: lean on sentiment
        }
    }

    private int smoothScore(int raw) {
        Integer prev = this.lastEmittedScore;
        if (prev == null) return raw;
        double smoothed = 0.60 * raw + 0.40 * prev;
        return clamp0to100((int) Math.round(smoothed));
    }

    private boolean shouldEmit(int score, String trend, String confBucket) {
        if (lastEmittedScore == null || lastEmittedTrend == null || lastConfidenceBucket == null) return true;
        if (!trend.equals(lastEmittedTrend)) return true;
        if (!confBucket.equals(lastConfidenceBucket)) return true;
        return Math.abs(score - lastEmittedScore) >= 1;
    }

    /**
     * ADX(14) from 5m candles; safe on short history (returns 0.0 if insufficient).
     */
    private double computeAdx14(IntradayCandleResponse ic) {
        List<IntradayCandleResponse.Candle> cs = candles(ic);
        if (cs == null || cs.size() < 16) return 0.0; // need prev candle + 14 window
        // Minimal Wilder ADX implementation
        double prevHigh = cs.get(0).getHigh();
        double prevLow = cs.get(0).getLow();
        double prevClose = cs.get(0).getClose();

        double tr14 = 0.0, plusDM14 = 0.0, minusDM14 = 0.0;

        for (int i = 1; i < cs.size(); i++) {
            double high = cs.get(i).getHigh();
            double low = cs.get(i).getLow();

            double upMove = Math.max(0.0, high - prevHigh);
            double downMove = Math.max(0.0, prevLow - low);

            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            double plusDM = (upMove > downMove && upMove > 0) ? upMove : 0.0;
            double minusDM = (downMove > upMove && downMove > 0) ? downMove : 0.0;

            if (i <= 14) {
                tr14 += tr;
                plusDM14 += plusDM;
                minusDM14 += minusDM;
            } else {
                tr14 = tr14 - (tr14 / 14.0) + tr;
                plusDM14 = plusDM14 - (plusDM14 / 14.0) + plusDM;
                minusDM14 = minusDM14 - (minusDM14 / 14.0) + minusDM;
            }

            prevHigh = high;
            prevLow = low;
            prevClose = cs.get(i).getClose();
        }

        if (tr14 <= 1e-8) return 0.0;
        double plusDI = (plusDM14 / tr14) * 100.0;
        double minusDI = (minusDM14 / tr14) * 100.0;
        double dx = (plusDI + minusDI) <= 1e-8 ? 0.0 : (Math.abs(plusDI - minusDI) / (plusDI + minusDI)) * 100.0;
        return dx; // good enough; full Wilder ADX smoothing beyond 14 is optional
    }

}
