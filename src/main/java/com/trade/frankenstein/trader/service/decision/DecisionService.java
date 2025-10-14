package com.trade.frankenstein.trader.service.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.config.DecisionServiceConfig;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.dto.*;
import com.trade.frankenstein.trader.enums.*;
import com.trade.frankenstein.trader.model.documents.DecisionQuality;
import com.trade.frankenstein.trader.model.documents.MarketSentimentSnapshot;
import com.trade.frankenstein.trader.model.documents.Trade;
import com.trade.frankenstein.trader.repo.documents.TradeRepo;
import com.trade.frankenstein.trader.service.*;
import com.trade.frankenstein.trader.service.advice.AdviceService;
import com.trade.frankenstein.trader.service.market.MarketDataService;
import com.trade.frankenstein.trader.service.news.NewsService;
import com.trade.frankenstein.trader.service.risk.PredictionService;
import com.trade.frankenstein.trader.service.risk.RiskService;
import com.trade.frankenstein.trader.service.sentiment.SentimentService;
import com.trade.frankenstein.trader.service.trade.TradesService;
import com.upstox.api.GetIntraDayCandleResponse;
import com.upstox.api.IntraDayCandleData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Fully enhanced DecisionService for auto trading bot.
 * Provides multi-strategy, predictive, risk- and portfolio-aware decision scoring.
 */
@Slf4j
@Service
public class DecisionService {
    private final PercentileWindow inferWindow = new PercentileWindow(256);

    // Freshness & throttles
    private static final long FRESH_SENTIMENT_SEC = 120;
    private static final Duration EMIT_MIN_GAP = Duration.ofSeconds(3);
    private static final double ADX_TREND_MIN = 20.0;

    // Underlying instrument
    private final String niftyKey = Underlyings.NIFTY;

    @Autowired
    private SentimentService sentimentService;
    @Autowired
    private MarketDataService marketDataService;
    @Autowired
    private OptionChainService optionChainService;
    @Autowired
    private StreamGateway streamGateway;
    @Autowired
    private EventPublisher eventPublisher;
    @Autowired
    private RiskService riskService;
    @Autowired
    private UpstoxService upstoxService;
    @Autowired
    private FastStateStore fast;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private TradeRepo tradeRepo;
    @Autowired
    private PortfolioService portfolioService;
    @Autowired
    private PredictionService mlService;
    @Autowired
    private DecisionServiceConfig config;
    @Autowired
    private TradesService tradesService;
    @Autowired
    private AdviceService adviceService;
    @Autowired
    private NewsService newsService;

    private volatile Integer lastScore;
    private volatile String lastTrend;
    private volatile String lastConfBucket;

    public Result<DecisionQuality> getQuality() {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) return Result.fail("user-not-logged-in");
        final long start = System.nanoTime();

        // 1) Build context
        DecisionContext ctx = buildDecisionContext();
        EnhancedMarketRegime regime = computeEnhancedRegime();
        PredictiveComponents preds = config.isEnableAdaptiveParameters()
                ? computePredictiveComponents() : PredictiveComponents.builder().build();
        MicrostructureSignals micro = computeMicrostructureScore();

        // 2) Base components
        int sScore = sentimentService.getNow().get().getScore();
        int regimeScore = normalizeRegimeScore(regime.getPrimary());
        BigDecimal momZ = safe(() -> marketDataService.getMomentumNow(Instant.now()).get()).orElse(BigDecimal.ZERO);

        // 3) ADX & weights
        double adx = safe(() -> computeAdx14(upstoxService.getIntradayCandleData(niftyKey, "minutes", "5"))).orElse(0.0);
        StrategyWeights w = chooseWeights(adx);

        // 4) Raw score
        AtomicReference<Double> raw = new AtomicReference<>(w.getWs() * sScore + w.getWr() * regimeScore + w.getWm() * clampMomScore(momZ));

        // 5) PCR tweak
        AtomicReference<Double> finalRaw = raw;
        safe(() -> {
            LocalDate exp = optionChainService.listNearestExpiries(niftyKey, 1).get().get(0);
            BigDecimal pcr = optionChainService.getOiPcr(niftyKey, exp).get();
            if (pcr.compareTo(new BigDecimal("1.2")) >= 0) finalRaw.updateAndGet(v -> (v + 5));
            else if (pcr.compareTo(new BigDecimal("0.8")) <= 0) finalRaw.updateAndGet(v -> (v - 5));
            return finalRaw;
        });

        // 6) Risk gates & adjustments
        boolean circuit = safe(() -> riskService.getCircuitState().get()).get();
        finalRaw.set(applyDynamicRiskAdjustments(finalRaw.get(), ctx, regime));

        // 7) Predictive adjustments
        finalRaw.set(config.isEnableAdaptiveParameters()
                ? applyPredictiveAdjustments(finalRaw.get().doubleValue(), preds, StrategyName.DQS) // DQS as example
                : finalRaw.get());

        // 8) Final score & smoothing
        int score = clamp0to100((int) Math.round(raw.get()));
        if (circuit) score = Math.min(score, 40);
        score = smoothScore(score);

        // 9) Confidence
        int confidence = 100;
        confidence -= isStaleSentiment() ? 20 : 0;
        confidence -= adx <= 0 ? 10 : 0;
        confidence = clamp0to100(confidence);
        String confBucket = confidence >= 80 ? "HIGH" : confidence >= 60 ? "MED" : "LOW";

        // 10) Reasons & tags
        List<String> reasons = generateReasons(sScore, regime, momZ, adx, circuit);
        Map<String, String> tags = generateTags(ctx, regime, micro, confidence);

        // 11) Build DTO
        DecisionQuality dq = new DecisionQuality(score, regime.getPrimary().name(), reasons, tags, Instant.now());

        // 12) Emit event
        if (fast.setIfAbsent("decision:emit", "1", EMIT_MIN_GAP) && shouldEmit(score, regime.getPrimary().name(), confBucket)) {
            JsonNode node = mapper.valueToTree(dq);
            streamGateway.publishDecision("quality", node.toPrettyString());
            publishDecisionEvent("decision.quality", dq, ctx, regime, preds);
            lastScore = score;
            lastTrend = regime.getPrimary().name();
            lastConfBucket = confBucket;
            log.info("decision.quality -> score={}, trend={}, conf={}", score, regime.getPrimary(), confBucket);
        }

        inferWindow.record((System.nanoTime() - start) / 1_000_000L);
        return Result.ok(dq);
    }

    // ========== Helper Methods ==========

    private DecisionContext buildDecisionContext() {
        return DecisionContext.builder()
                .portfolio(safe(() -> portfolioService.getPortfolioSummary().get()).orElse(null))
                .activeTrades(safe(() -> tradesService.getActiveTrades()).orElse(Collections.emptyList()))
                .pendingAdviceByStrategy(safe(() -> adviceService.getPendingCountsByStrategy()).orElse(Map.of()))
                .totalExposure(calculateExposure())
                .netDelta(calculateNetDelta())
                .portfolioBias(PortfolioBias.fromNetDelta(calculateNetDelta().doubleValue()))
                .concentrationRisk(calculateConcentrationRisk())
                .build();
    }

    private EnhancedMarketRegime computeEnhancedRegime() {
        try {
            MarketRegime r5 = marketDataService.getRegimeOn("minutes", "5").orElse(MarketRegime.NEUTRAL);
            MarketRegime r15 = marketDataService.getRegimeOn("minutes", "15").orElse(MarketRegime.NEUTRAL);
            MarketRegime r60 = marketDataService.getRegimeOn("minutes", "60").orElse(MarketRegime.NEUTRAL);
            BigDecimal vix = BigDecimal.valueOf(marketDataService.getVixProxyPct(Underlyings.NIFTY).get());
            BigDecimal atrPct = BigDecimal.valueOf(marketDataService.getAtrJump5mPct(niftyKey).get());
            double volRatio = marketDataService.getConcentrationRatio(niftyKey).orElse(1.0);
            boolean newsBurst = newsService.getRecentBurstCount(10).orElse(0) >= 5;
            return EnhancedMarketRegime.builder()
                    .primary(r15).shortTerm(r5).mediumTerm(r60)
                    .volatilityLevel(classifyVolatility(vix, atrPct))
                    .volumeProfile(classifyVolume(volRatio))
                    .newsImpact(newsBurst)
                    .regimeStrength(calculateRegimeStrength(r5, r15, r60))
                    .regimeConsistency(calculateRegimeConsistency(r5, r15, r60))
                    .build();
        } catch (Exception e) {
            log.debug("Enhanced regime failed: {}", e.getMessage());
            return EnhancedMarketRegime.neutral();
        }
    }

    private PredictiveComponents computePredictiveComponents() {
        try {
            var dir = mlService.predictDirection(niftyKey, 30).orElse(null);
            var vol = mlService.predictVolatility(niftyKey, 60).orElse(null);
            var flow = optionChainService.analyzeOptionsFlow(niftyKey, LocalDate.now()).orElse(null);
            var micro = marketDataService.getMicrostructure(niftyKey).orElse(null);
            return PredictiveComponents.builder()
                    .shortTermDirection(dir)
                    .volatilityForecast(vol)
                    .optionsFlowBias(flow)
                    .microstructureSignals(micro)
                    .predictionConfidence(calculatePredictionConfidence(dir, vol))
                    .build();
        } catch (Exception e) {
            log.debug("Predictive comps failed: {}", e.getMessage());
            return PredictiveComponents.builder().build();
        }
    }

    /**
     * Combines prediction confidences for direction, volatility, options flow, and microstructure.
     * Returns an aggregate confidence value between 0 and 1.
     */
    /**
     * Calculates overall prediction confidence from direction and volatility predictions.
     */
    private double calculatePredictionConfidence(DirectionPrediction dir, VolatilityDecisionPrediction vol) {
        double c1 = dir != null ? dir.getConfidence() : 0.0;
        double c2 = vol != null ? vol.getConfidence() : 0.0;
        int count = 0;
        if (dir != null) count++;
        if (vol != null) count++;
        return count > 0 ? Math.max(0, Math.min(1.0, (c1 + c2) / count)) : 0.0;
    }


    private MicrostructureSignals computeMicrostructureScore() {
        try {
            BigDecimal spread = marketDataService.getBidAskSpread(niftyKey).orElse(BigDecimal.ZERO);
            BigDecimal imbalance = marketDataService.getOrderBookImbalance(niftyKey).orElse(BigDecimal.ZERO);
            BigDecimal skew = marketDataService.getTradeSizeSkew(niftyKey).orElse(BigDecimal.ZERO);
            double depth = marketDataService.getDepthScore(niftyKey).orElse(0.0);
            double impact = marketDataService.getPriceImpact(niftyKey).orElse(0.0);
            return new MicrostructureSignals(spread, imbalance, skew, depth, impact, Instant.now());
        } catch (Exception e) {
            log.debug("Microstructure failed: {}", e.getMessage());
            return new MicrostructureSignals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, Instant.now());
        }
    }

    private double applyDynamicRiskAdjustments(double base, DecisionContext ctx, EnhancedMarketRegime reg) {
        double s = base;
        if (ctx.getTotalExposure().compareTo(BigDecimal.valueOf(1_000_000)) > 0) s *= 0.85;
        if (ctx.getConcentrationRisk() > 0.4) s *= 0.7;
        switch (reg.getVolatilityLevel()) {
            case HIGH -> s *= 0.75;
            case LOW -> s *= 1.1;
        }
        double cons = reg.getRegimeConsistency();
        if (cons > 0.8) s *= 1.15;
        else if (cons < 0.3) s *= 0.8;
        double dailyLoss = safe(() -> riskService.getDailyLossPct()).orElse(0.0);
        if (dailyLoss > 50) s *= 0.6;
        else if (dailyLoss > 25) s *= 0.8;
        return s;
    }

    private double applyPredictiveAdjustments(double base, PredictiveComponents p, StrategyName strat) {
        if (p.isEmpty()) return base;
        double s = base;
        if (p.getShortTermDirection() != null && p.getShortTermDirection().getConfidence() > 0.7) {
            if (isAligned(strat, p.getShortTermDirection().getDirection())) s *= 1.2;
            else s *= 0.8;
        }
        if (p.getVolatilityForecast() != null) {
            var v = p.getVolatilityForecast();
            if (strat.isOptionsStrategy() && v.getExpectedChange() > 0.2 && v.getConfidence() > 0.6) s *= 1.3;
            if ((strat == StrategyName.SCALPING || strat == StrategyName.MOMENTUM)
                    && v.getExpectedChange() < -0.2 && v.getConfidence() > 0.6) s *= 0.7;
        }
        return s;
    }

    /**
     * Checks if the given strategy is directionally aligned with the forecast.
     */
    private boolean isAligned(StrategyName strategy, DirectionPrediction.Direction direction) {
        if (strategy == null || direction == null) return false;
        switch (strategy) {
            // Up-directional strategies
            case MOMENTUM:
            case BREAKOUT:
            case SWING:
            case VOLATILITY_EXPANSION:
            case SCALPING:
            case MACD_SIGNAL:
            case NEWS_REACTION:
            case EARNINGS_PLAY:
            case SENTIMENT_BASED:
            case ML_PREDICTION:
                return direction == DirectionPrediction.Direction.UP;

            // Down-directional strategies
            case MEAN_REVERSION:
            case STAT_ARB:
            case PAIRS:
            case RSI_DIVERGENCE:
            case VOLATILITY_CONTRACTION:
            case ANTI_MARTINGALE:
            case PATTERN_RECOGNITION:
                return direction == DirectionPrediction.Direction.DOWN;

            // Option, risk, grid, band, DQS, and other market-neutral or bidirectional strategies
            case OPTION_STRADDLE:
            case OPTION_STRANGLE:
            case IRON_FLY:
            case IRON_CONDOR:
            case GRID_TRADING:
            case MARTINGALE:
            case BOLLINGER_BANDS:
            case DQS:
            case RISK_MANAGEMENT:
            case STOP_LOSS:
            case TAKE_PROFIT:
            case PORTFOLIO_HEDGE:
                return true;

            default:
                return true;
        }
    }


    private DecisionQuality generateFinalQuality(int score, String trend, List<String> reasons,
                                                 Map<String, String> tags) {
        return new DecisionQuality(score, trend, reasons, tags, Instant.now());
    }

// ================= Helper Methods =================

    private static int clamp0to100(int v) {
        return Math.max(0, Math.min(100, v));
    }

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
            default:
                return 50;
        }
    }

    private int clampMomScore(BigDecimal z) {
        return clamp0to100((int) Math.round(50 + (z == null ? 0 : z.doubleValue()) * 20));
    }

    private StrategyWeights chooseWeights(double adx) {
        if (adx >= ADX_TREND_MIN) {
            return new StrategyWeights(0.20, 0.40, 0.40);
        } else {
            return new StrategyWeights(0.50, 0.25, 0.25);
        }
    }

    private int smoothScore(int raw) {
        Integer prev = lastScore;
        if (prev == null) return raw;
        double sm = raw * 0.6 + prev * 0.4;
        return clamp0to100((int) Math.round(sm));
    }

    private boolean shouldEmit(int score, String trend, String conf) {
        if (lastScore == null || lastTrend == null || lastConfBucket == null) return true;
        if (!trend.equals(lastTrend)) return true;
        if (!conf.equals(lastConfBucket)) return true;
        return Math.abs(score - lastScore) >= 1;
    }

    private boolean isStaleSentiment() {
        try {
            MarketSentimentSnapshot s = sentimentService.getNow().get();
            return s != null && Duration.between(s.getAsOf(), Instant.now()).getSeconds() > FRESH_SENTIMENT_SEC;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isLoggedIn() {
        try {
            return AuthCodeHolder.getInstance().isLoggedIn();
        } catch (Exception e) {
            return false;
        }
    }

    // Fallback wrapper
    private <T> Optional<T> safe(Supplier<T> sup) {
        try {
            return Optional.ofNullable(sup.get());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    // ADX(14) computation
    private double computeAdx14(GetIntraDayCandleResponse ic) {
        IntraDayCandleData cs = ic == null ? null : ic.getData();
        if (cs == null || cs.getCandles().size() < 16) return 0;
        List<List<Object>> rows = new ArrayList<>(cs.getCandles());
        rows.sort(Comparator.comparingLong(r -> toEpoch(r.get(0))));
        double prevHigh = toNum(rows.get(0).get(2)), prevLow = toNum(rows.get(0).get(3)), prevClose = toNum(rows.get(0).get(4));
        double tr14 = 0, plus14 = 0, minus14 = 0;
        for (int i = 1; i < rows.size(); i++) {
            List<Object> r = rows.get(i);
            double h = toNum(r.get(2)), l = toNum(r.get(3)), c = toNum(r.get(4));
            double tr = Math.max(h - l, Math.max(Math.abs(h - prevClose), Math.abs(l - prevClose)));
            double up = Math.max(0, h - prevHigh), dn = Math.max(0, prevLow - l);
            double pd = up > dn ? up : 0, md = dn > up ? dn : 0;
            if (i <= 14) {
                tr14 += tr;
                plus14 += pd;
                minus14 += md;
            } else {
                tr14 = tr14 - tr14 / 14 + tr;
                plus14 = plus14 - plus14 / 14 + pd;
                minus14 = minus14 - minus14 / 14 + md;
            }
            prevHigh = h;
            prevLow = l;
            prevClose = c;
        }
        if (tr14 < 1e-9) return 0;
        double plusDI = plus14 / tr14 * 100, minusDI = minus14 / tr14 * 100;
        double dx = Math.abs(plusDI - minusDI) / (plusDI + minusDI) * 100;
        return dx;
    }

    private long toEpoch(Object ts) {
        long v = ts instanceof Number ? ((Number) ts).longValue() : Long.parseLong(ts.toString());
        return v > 3_000_000_000L ? v : v * 1000L;
    }

    private double toNum(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(o.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    // Accuracy
    private Integer computeAccuracyFromClosedTrades(int window) {
        Page<Trade> page = tradeRepo.findAll(PageRequest.of(0, window * 2, Sort.by("exitTime").descending()));
        int win = 0, tot = 0;
        for (Trade t : page) {
            if (t.getStatus() != TradeStatus.CLOSED) continue;
            Double pnl = safe(() -> {
                Double p = t.getPnl();
                if (p != null) return p;
                Double e = t.getEntryPrice(), c = t.getCurrentPrice();
                int q = t.getQuantity();
                return (t.getSide() == OrderSide.SELL ? e - c : c - e) * q;
            }).orElse(null);
            if (pnl == null) continue;
            tot++;
            if (pnl > 0) win++;
            if (tot >= window) break;
        }
        return tot == 0 ? null : clamp0to100((int) Math.round(win * 100.0 / tot));
    }

    // Context calculations (stubs—implement per your data)
    private BigDecimal calculateExposure() {
        return BigDecimal.ZERO;
    }

    private BigDecimal calculateNetDelta() {
        return BigDecimal.ZERO;
    }

    private double calculateConcentrationRisk() {
        return 0.0;
    }

    // Reasons & tags
    private List<String> generateReasons(int sScore, EnhancedMarketRegime r, BigDecimal mZ, double adx, boolean cir) {
        List<String> rs = new ArrayList<>();
        rs.add("Sentiment " + sScore);
        rs.add("Regime " + r.getPrimary());
        rs.add("Momentum " + clampMomScore(mZ));
        rs.add("ADX " + String.format("%.1f", adx));
        if (cir) rs.add("Circuit TRIPPED");
        return rs;
    }

    private Map<String, String> generateTags(DecisionContext ctx, EnhancedMarketRegime r,
                                             MicrostructureSignals micro, int conf) {
        Map<String, String> m = new HashMap<>();
        m.put("Exposure", ctx.getTotalExposure().toString());
        m.put("Bias", ctx.getPortfolioBias().name());
        m.put("VolLevel", r.getVolatilityLevel().name());
        m.put("Spread", micro.getBidAskSpread().toString());
        m.put("Confidence", String.valueOf(conf));
        return m;
    }

    // Enhanced event publishing
    private void publishDecisionEvent(String evt, DecisionQuality dq,
                                      DecisionContext ctx,
                                      EnhancedMarketRegime r,
                                      PredictiveComponents p) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("ts", Instant.now().toEpochMilli());
            o.addProperty("event", evt);
            o.addProperty("score", dq.getScore());
            o.addProperty("trend", dq.getTrend().name());
            // add context, regime, predictions as needed...
            eventPublisher.publish(EventBusConfig.TOPIC_DECISION, dq.getTrend().name(), o.toString());
        } catch (Exception ignored) {
        }
    }

    // Determines volatility regime based on vix proxy and ATR%
    private VolatilityLevel classifyVolatility(BigDecimal vixProxyPct, BigDecimal atrPct) {
        if ((vixProxyPct != null && vixProxyPct.doubleValue() > 30) ||
                (atrPct != null && atrPct.doubleValue() > 1.5))
            return VolatilityLevel.HIGH;
        else if ((vixProxyPct != null && vixProxyPct.doubleValue() < 15) &&
                (atrPct != null && atrPct.doubleValue() < 0.5))
            return VolatilityLevel.LOW;
        else
            return VolatilityLevel.MEDIUM;
    }

    // Categorize volume profile
    private VolumeProfile classifyVolume(double ratio) {
        if (ratio >= 0.10) return VolumeProfile.HIGH;
        if (ratio >= 0.03) return VolumeProfile.NORMAL;
        return VolumeProfile.LOW;
    }

    // Computes regime "strength" as how far short/medium/long terms agree (0..1)
    private double calculateRegimeStrength(MarketRegime shortTerm, MarketRegime mediumTerm, MarketRegime longTerm) {
        int same = 0;
        if (shortTerm == mediumTerm) same++;
        if (mediumTerm == longTerm) same++;
        if (shortTerm == longTerm) same++;
        return same / 3.0; // 1.0 = all agree, 0 = none agree
    }

    // Computes regime "consistency" as: 1 if all agree, 0.66 if 2 agree, 0.33 if none
    private double calculateRegimeConsistency(MarketRegime shortTerm, MarketRegime mediumTerm, MarketRegime longTerm) {
        int bullCount = 0, bearCount = 0, neutralCount = 0;
        for (MarketRegime r : new MarketRegime[]{shortTerm, mediumTerm, longTerm}) {
            if (r == MarketRegime.BULLISH) bullCount++;
            else if (r == MarketRegime.BEARISH) bearCount++;
            else neutralCount++;
        }
        int max = Math.max(bullCount, Math.max(bearCount, neutralCount));
        return max / 3.0;
    }

    /**
     * Computes accuracy (win rate %) for closed trades of the given strategy over the lookback window in hours.
     * Returns accuracy as Integer in [0, 100], or null if insufficient data.
     */
    public Integer computeEnhancedAccuracy(StrategyName strategy, int windowHours) {
        if (strategy == null || windowHours <= 0) return null;
        Instant windowStart = Instant.now().minus(Duration.ofHours(windowHours));
        int win = 0, total = 0;

        // Find closed trades matching strategy within window
        List<Trade> trades = tradeRepo.findByStrategyAndStatusAndExitTimeAfter(
                strategy, TradeStatus.CLOSED, windowStart);

        for (Trade trade : trades) {
            Double pnl = trade.getPnl();
            if (pnl == null) continue;
            total++;
            if (pnl > 0) win++;
        }
        return total > 0 ? Math.max(0, Math.min(100, (int) Math.round((win * 100.0) / total))) : null;
    }

    public long getInferenceP95Millis() {
        return inferWindow.percentile(95);
    }
}
