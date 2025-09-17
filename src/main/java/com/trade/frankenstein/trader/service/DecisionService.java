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
import java.math.RoundingMode;
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

    /**
     * Primary endpoint for RegimeDecisionCard.
     */
    public Result<DecisionQuality> getQuality() {
        try {
            // ----- Sentiment -----
            int sScore = 50;
            try {
                Result<MarketSentimentSnapshot> sRes = sentimentService.getNow();
                if (sRes != null && sRes.isOk() && sRes.get() != null) {
                    Integer sc = sRes.get().getScore();
                    sScore = clamp0to100(sc == null ? 50 : sc);
                } else {
                    log.debug("getQuality(): sentiment fallback used");
                }
            } catch (Throwable t) {
                log.debug("getQuality(): sentiment unavailable: {}", t.getMessage());
            }

            // ----- Regime -----
            MarketRegime regimeNow = null;
            int regimeScore = 50;
            try {
                Result<MarketRegime> r = marketDataService.getRegimeNow();
                if (r != null && r.isOk() && r.get() != null) {
                    regimeNow = r.get();
                    regimeScore = normalizeRegimeScore(regimeNow);
                }
            } catch (Throwable t) {
                log.debug("getQuality(): regime unavailable: {}", t.getMessage());
            }

            // ----- Momentum z-score -----
            BigDecimal momZ = BigDecimal.ZERO;
            int momScore = 50;
            try {
                Result<BigDecimal> m = marketDataService.getMomentumNow(Instant.now());
                if (m != null && m.isOk() && m.get() != null) {
                    momZ = m.get();
                    // map z≈[-2,2] → 0..100
                    momScore = clamp0to100((int) Math.round(50 + momZ.doubleValue() * 20));
                }
            } catch (Throwable t) {
                log.debug("getQuality(): momentum unavailable: {}", t.getMessage());
            }

            // ----- Blend -----
            int score = (int) Math.round(0.55 * sScore + 0.30 * regimeScore + 0.15 * momScore);
            String trend = (regimeNow == null) ? "NEUTRAL" : regimeNow.name();

            // ----- Reasons -----
            List<String> reasons = new ArrayList<String>();
            reasons.add("Sentiment " + sScore);
            reasons.add("Regime " + trend);
            reasons.add("Momentum z≈" + momZ.setScale(2, RoundingMode.HALF_UP));

            // Optional PCR context (nearest expiry)
            try {
                Result<List<LocalDate>> expsRes = optionChainService.listNearestExpiries(niftyUnderlyingKey, 1);
                if (expsRes != null && expsRes.isOk() && expsRes.get() != null && !expsRes.get().isEmpty()) {
                    LocalDate expiry = expsRes.get().get(0);
                    Result<BigDecimal> pcrRes = optionChainService.getOiPcr(niftyUnderlyingKey, expiry);
                    if (pcrRes != null && pcrRes.isOk() && pcrRes.get() != null) {
                        reasons.add("PCR " + pcrRes.get().setScale(2, RoundingMode.HALF_UP));
                    }
                }
            } catch (Throwable t) {
                log.debug("getQuality(): PCR context unavailable: {}", t.getMessage());
            }

            // ----- Tags (RR / Slippage / Throttle) -----
            Map<String, String> tags = computeTags(niftyUnderlyingKey, regimeNow, sScore, momZ);

            DecisionQuality dto = new DecisionQuality(score, trend, reasons, tags, Instant.now());

            // Emit SSE (best-effort)
            try {
                streamGateway.send("decision.quality", dto);
                log.info("decision.quality -> score={}, trend={}, s={}, r={}, mZ={}",
                        score, trend, sScore, regimeScore, momZ);
            } catch (Throwable t) {
                log.info("getQuality(): SSE send failed: {}", t.getMessage(), t);
            }

            return Result.ok(dto);
        } catch (Throwable t) {
            log.error("getQuality() failed", t);
            return Result.fail(t);
        }
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
        } catch (Throwable ignored) {
            // keep default
        }
        map.put("RR", rrTag);

        // ---- 2) Slippage tag (1-min live bar roughness vs MAX_SLIPPAGE_PCT) ----
        String slipTag = "LOW";
        try {
            OHLC_Quotes q = upstoxService.getMarketOHLCQuote(underlyingKey, "1minute");
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
        } catch (Throwable ignored) { /* keep default */ }
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
        } catch (Throwable ignored) { /* keep default */ }
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
        } catch (Throwable t) {
            // Defensive: if model isn't updated yet, fail safely
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
}
