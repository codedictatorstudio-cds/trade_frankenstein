package com.trade.frankenstein.trader.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.common.constants.BotConsts;
import com.trade.frankenstein.trader.common.constants.RiskConstants;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.enums.FlagName;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.model.documents.DecisionQuality;
import com.trade.frankenstein.trader.model.documents.MarketSentimentSnapshot;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.upstox.api.*;
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

    // freshness windows
    private static final long FRESH_SENTIMENT_SEC = 120;
    private static final long FRESH_OHLC_SEC = 90;
    // emit throttle
    private static final Duration EMIT_MIN_GAP = Duration.ofSeconds(3);
    private static final double ADX_TREND_MIN = 20.0;
    /**
     * Upstox underlying instrument_key for NIFTY index (used by OptionChainService and OHLC endpoints).
     * Example defaults:
     * - Index key: "NSE_INDEX|Nifty 50"
     * - Derivatives root: "NFO:NIFTY50-INDEX"
     */
    private final String niftyUnderlyingKey = Underlyings.NIFTY;
    @Autowired
    private SentimentService sentimentService;
    @Autowired
    private MarketDataService marketDataService;
    @Autowired
    private OptionChainService optionChainService;
    @Autowired
    private StreamGateway streamGateway;
    @Autowired
    private EventPublisher eventPublisher; // Step-10: Kafka publish for decision
    @Autowired
    private RiskService riskService;
    @Autowired
    private UpstoxService upstoxService;
    @Autowired
    private FlagsService flagsService;
    @Autowired
    private FastStateStore fast;


    private volatile Integer lastEmittedScore = null;
    private volatile String lastEmittedTrend = null;
    private volatile String lastConfidenceBucket = null;

    private static int clamp0to100(int v) {
        return Math.max(0, Math.min(100, v));
    }

    // -------- helpers --------

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

    private static IntraDayCandleData candles(GetIntraDayCandleResponse ic) {
        if (ic == null) return null;
        try {
            IntraDayCandleData list = ic.getData();
            return list;
        } catch (Exception t) {
            log.debug("candles() parse failed: {}", t.toString());
            return null;
        }
    }

    private static double lastClose(GetIntraDayCandleResponse ic) {
        IntraDayCandleData cs = candles(ic);
        if (cs == null) return 0.0;
        List<List<Object>> list = cs.getCandles();
        List<Object> last = (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);
        if (last == null || last.size() < 5) return 0.0;
        Object closeObj = last.get(4);
        if (!(closeObj instanceof Number)) return 0.0;
        return ((Number) closeObj).doubleValue();
    }

    // -------- IntradayCandleResponse helpers (array-of-arrays → typed list) --------

    /**
     * Compute ATR (simple TR average) over last {@code n} candles.
     * Expects candles in chronological order.
     */
    private static double computeAtr(GetIntraDayCandleResponse ic, int n) {
        IntraDayCandleData cs = candles(ic);
        if (cs == null || cs.getCandles() == null) return 0.0;
        int len = cs.getCandles().size();
        if (len < 2) return 0.0;

        int lookback = Math.min(n, len - 1);
        double sumTR = 0.0;

        for (int i = len - lookback; i < len; i++) {
            List<Object> cur = cs.getCandles().get(i);
            List<Object> prev = cs.getCandles().get(i - 1);

            double curHigh = toNumber(cur.get(2));
            double curLow = toNumber(cur.get(3));
            double prevClose = toNumber(prev.get(4));

            double highLow = curHigh - curLow;
            double highPrev = Math.abs(curHigh - prevClose);
            double lowPrev = Math.abs(curLow - prevClose);

            double tr = Math.max(highLow, Math.max(highPrev, lowPrev));
            if (tr > 0.0) sumTR += tr;
        }
        return sumTR / lookback;
    }

    private static double toNumber(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0.0;
        }
    }

    public Result<DecisionQuality> getQuality() {
        // ----- Risk gates (Step-9) -----
        boolean headroomOk = false;
        boolean slCooldownActive = false;
        boolean twoSlLockActive = false;
        boolean dailyLossLock = false;
        try {
            headroomOk = riskService.hasHeadroom(0.0d);
        } catch (Exception ignored) {
        }
        try {
            if (flagsService.isOn(FlagName.SL_COOLDOWN_ENABLED)) {
                int mins = riskService.getMinutesSinceLastSl(niftyUnderlyingKey);
                slCooldownActive = (mins >= 0) && (mins < BotConsts.Risk.SL_COOLDOWN_MINUTES);
            }
        } catch (Exception ignored) {
        }
        try {
            if (flagsService.isOn(FlagName.DISABLE_REENTRY_AFTER_2_SL)) {
                int rs2 = riskService.getRestrikesToday(niftyUnderlyingKey);
                twoSlLockActive = rs2 >= 2;
            }
        } catch (Exception ignored) {
        }
        try {
            if (flagsService.isOn(FlagName.DAILY_LOSS_GUARD)) {
                Result<Boolean> c = riskService.getCircuitState();
                dailyLossLock = (c != null && c.isOk() && Boolean.TRUE.equals(c.get()));
            }
        } catch (Exception ignored) {
        }

        if (!isLoggedIn()) return Result.fail("user-not-logged-in");
        try {
            final Instant now = Instant.now();

            // hard entry-block flags influence the score/trend we publish
            boolean hardBlock =
                    flagsService.isOn(FlagName.KILL_SWITCH_OPEN_NEW)
                            || flagsService.isOn(FlagName.CIRCUIT_BREAKER_LOCKOUT);

            boolean timeBlock =
                    flagsService.isOn(FlagName.OPENING_5M_BLACKOUT)
                            || flagsService.isOn(FlagName.NOON_PAUSE_WINDOW)
                            || flagsService.isOn(FlagName.LATE_ENTRY_CUTOFF);

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
                    log.debug("getQuality(): sentiment fallback used");
                }
            } catch (Exception t) {
                log.debug("getQuality(): sentiment unavailable: {}", t.toString());
            }

            // ----- Regime -----
            MarketRegime regimeNow = null;
            int regimeScore = 50;
            try {
                Result<MarketRegime> r = marketDataService.getRegimeNow();
                if (r != null && r.isOk()) {
                    regimeNow = r.get();
                    regimeScore = normalizeRegimeScore(regimeNow);
                }
            } catch (Exception t) {
                log.debug("getQuality(): regime unavailable: {}", t.toString());
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
                log.debug("getQuality(): momentum unavailable: {}", t.toString());
            }

            // ----- Structure (ADX) for adaptive weights -----
            double adx14 = 0.0;
            try {
                GetIntraDayCandleResponse c5 = upstoxService.getIntradayCandleData(niftyUnderlyingKey, "minutes", "5");
                adx14 = computeAdx14(c5); // helper below (safe on short history)
            } catch (Exception t) {
                log.debug("getQuality(): ADX unavailable: {}", t.toString());
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
                        if (pcr.compareTo(new BigDecimal("1.20")) > 0) raw += 5;      // CE tilt
                        else if (pcr.compareTo(new BigDecimal("0.80")) < 0) raw -= 5; // PE tilt
                    }
                }
            } catch (Exception t) {
                log.debug("getQuality(): PCR context unavailable: {}", t.toString());
            }

            // ----- Risk: circuit breaker → cap & neutralize -----
            boolean circuitTripped = false;
            try {
                Result<RiskSnapshot> r = riskService.getSummary();
                if (r != null && r.isOk() && r.get() != null) {
                    circuitTripped = isCircuitLikeTripped(r.get());
                }
            } catch (Exception ignored) {
            }

            // ----- Confidence (freshness + availability) -----
            int confidence = 100;
            if (sAsOf != null && Duration.between(sAsOf, now).getSeconds() > FRESH_SENTIMENT_SEC) confidence -= 20;
            if (adx14 <= 0.0) confidence -= 10; // missing structure
            if (momZ == null) confidence -= 10; // missing momentum
            confidence = clamp0to100(confidence);

            // Final trend
            String trend = (regimeNow == null ? "NEUTRAL" : regimeNow.name());

            // Risk/flags cap
            int score = clamp0to100((int) Math.round(raw));
            if (circuitTripped || hardBlock) {
                trend = "NEUTRAL";
                score = Math.min(score, 40); // don’t claim high quality under a hard stop
            } else if (timeBlock) {
                score = Math.min(score, 55); // softer cap for time windows
            }

            // Smooth & de-noise emit
            int smoothScore = smoothScore(score);
            String confBucket = (confidence >= 80 ? "HIGH" : (confidence >= 60 ? "MED" : "LOW"));

            // ----- Reasons & tags -----
            List<String> reasons = new ArrayList<String>();
            reasons.add("Sentiment " + sScore);
            reasons.add("Regime " + trend);
            reasons.add("Momentum z≈" + momZ);
            reasons.add("ADX " + String.format("%.1f", adx14));
            if (circuitTripped) reasons.add("Circuit TRIPPED");
            if (hardBlock) reasons.add("KillSwitch ON");
            if (timeBlock) reasons.add("TimeWindow");

            Map<String, String> tags = computeTags(niftyUnderlyingKey, regimeNow, sScore, momZ);
            // Risk tags
            tags.put("Headroom", headroomOk ? "OK" : "NONE");
            if (slCooldownActive) tags.put("Cooldown", "ACTIVE");
            if (twoSlLockActive) tags.put("2SL", "LOCKED");
            if (dailyLossLock || circuitTripped) tags.put("DailyLoss", "LOCKED");

            tags.put("Confidence", String.valueOf(confidence));
            if (hardBlock || !headroomOk || slCooldownActive || twoSlLockActive || dailyLossLock || circuitTripped)
                tags.put("Entry", "BLOCKED");
            else if (timeBlock) tags.put("Entry", "WINDOW");

            DecisionQuality dto = new DecisionQuality(smoothScore, trend, reasons, tags, now);

            // emit with throttle + change filter
            boolean okToEmit = fast.setIfAbsent("decision:emit", "1", EMIT_MIN_GAP);
            if (okToEmit && shouldEmit(smoothScore, trend, confBucket)) {
                streamGateway.publishDecision("quality", dto);
                publishDecisionEvent("decision.quality", dto); // Step-10
                lastEmittedScore = smoothScore;
                lastEmittedTrend = trend;
                lastConfidenceBucket = confBucket;
                log.info("decision.quality -> score={}, trend={}, conf={}, hardBlock={}, timeBlock={}",
                        smoothScore, trend, confBucket, hardBlock, timeBlock);
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
        if (rs.getRiskBudgetLeft() != null && rs.getRiskBudgetLeft() <= 0.0) return true;
        if (rs.getDailyLossPct() != null && rs.getDailyLossPct() >= 100.0) return true;

        // 2) Lots guardrail breached
        Integer used = rs.getLotsUsed();
        Integer cap = rs.getLotsCap();
        if (used != null && cap != null && cap > 0 && used >= cap) return true;

        // 3) Order rate limiter blown
        return rs.getOrdersPerMinPct() != null && rs.getOrdersPerMinPct() >= 100.0;
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
            GetIntraDayCandleResponse ic =
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
            GetMarketQuoteOHLCResponseV3 q = upstoxService.getMarketOHLCQuote(underlyingKey, "I1");
            if (q != null && q.getData() != null) {
                MarketQuoteOHLCV3 d = q.getData().get(underlyingKey);
                if (d != null && d.getLiveOhlc() != null) {
                    OhlcV3 o = d.getLiveOhlc();
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
            log.debug("computeTags(): throttle tag unavailable: {}", ignored.toString());
        }
        map.put("Throttle", thrTag);

        return map;
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
    private double computeAdx14(GetIntraDayCandleResponse ic) {
        IntraDayCandleData cs = candles(ic);
        if (cs == null || cs.getCandles() == null || cs.getCandles().size() < 16) return 0.0;
        List<Object> prev = cs.getCandles().get(0);

        double prevClose = toNumber(prev.get(4));
        double prevHigh = toNumber(prev.get(2));
        double prevLow = toNumber(prev.get(3));

        double tr14 = 0.0, plusDM14 = 0.0, minusDM14 = 0.0;

        for (int i = 1; i < cs.getCandles().size(); i++) {
            List<Object> row = cs.getCandles().get(i);
            double high = toNumber(row.get(2));
            double low = toNumber(row.get(3));

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
            prevClose = toNumber(row.get(4));
        }

        if (tr14 <= 1e-8) return 0.0;
        double plusDI = (plusDM14 / tr14) * 100.0;
        double minusDI = (minusDM14 / tr14) * 100.0;
        double denom = (plusDI + minusDI);
        double dx = denom <= 1e-8 ? 0.0 : (Math.abs(plusDI - minusDI) / denom) * 100.0;
        return dx; // good enough; full Wilder ADX smoothing beyond 14 is optional
    }

    private boolean isLoggedIn() {
        try {
            return AuthCodeHolder.getInstance().isLoggedIn();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Step-10: Publish a lightweight decision event to Kafka. Best-effort, never breaks flow.
     */
    private void publishDecisionEvent(String event, DecisionQuality q) {
        try {
            if (eventPublisher == null || q == null) return;
            JsonObject o = new JsonObject();
            o.addProperty("ts", java.time.Instant.now().toEpochMilli());
            o.addProperty("event", event);
            o.addProperty("source", "decision");
            o.addProperty("score", q.getScore());
            if (q.getTrend() != null) o.addProperty("trend", q.getTrend().name());

            // reasons -> JsonArray
            try {
                java.util.List<String> rs = q.getReasons();
                if (rs != null && !rs.isEmpty()) {
                    JsonArray arr = new JsonArray();
                    for (int i = 0; i < rs.size(); i++) {
                        arr.add(rs.get(i));
                    }
                    o.add("reasons", arr);
                }
            } catch (Throwable ignore) {
            }

            // tags -> nested object
            try {
                Map<String, String> tags = null;
                try {
                    int sScore = 50;
                    try {
                        Result<MarketSentimentSnapshot> sRes = sentimentService.getNow();
                        if (sRes != null && sRes.isOk() && sRes.get() != null) {
                            Integer sc = sRes.get().getScore();
                            sScore = sc == null ? 50 : Math.max(0, Math.min(100, sc));
                        }
                    } catch (Exception ignore2) { /* keep default */ }
                    MarketRegime regimeNow = MarketRegime.NEUTRAL;
                    try {
                        String t = q.getTrend().name();
                        if (t != null) regimeNow = MarketRegime.valueOf(t.toUpperCase());
                    } catch (Exception ignore2) {
                        regimeNow = MarketRegime.NEUTRAL;
                    }
                    BigDecimal momZ = BigDecimal.ZERO;
                    try {
                        Result<java.math.BigDecimal> m = marketDataService.getMomentumNow(java.time.Instant.now());
                        if (m != null && m.isOk() && m.get() != null) momZ = m.get();
                    } catch (Exception ignore2) { /* keep default */ }
                    tags = computeTags(niftyUnderlyingKey, regimeNow, sScore, momZ);
                } catch (Throwable ignore3) {
                    tags = null;
                }
                if (tags != null && !tags.isEmpty()) {
                    JsonObject to = new JsonObject();
                    for (java.util.Map.Entry<String, String> e : tags.entrySet()) {
                        if (e.getKey() == null) continue;
                        String k = e.getKey();
                        String v = e.getValue();
                        if (v != null) to.addProperty(k, v);
                    }
                    o.add("tags", to);
                }
            } catch (Throwable ignore) {
            }
            // key preference: trend bucket -> "decision"
            String key = q.getTrend().name();
            if (key == null || key.trim().isEmpty()) key = "decision";
            eventPublisher.publish(EventBusConfig.TOPIC_DECISION, key, o.toString());
        } catch (Throwable ignore) {
        }
    }

    private record Weights(double ws, double wr, double wm) {
    }
}
