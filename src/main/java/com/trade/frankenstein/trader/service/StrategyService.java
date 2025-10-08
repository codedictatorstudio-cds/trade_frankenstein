package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.enums.OptionType;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.model.documents.DecisionQuality;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.service.decision.DecisionService;
import com.trade.frankenstein.trader.service.market.MarketDataService;
import com.trade.frankenstein.trader.service.sentiment.SentimentService;
import com.upstox.api.*;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class StrategyService {

    private static final String NIFTY = Underlyings.NIFTY;

    // ----- Indicator & filter params -----
    private static final int EMA_FAST = 20, EMA_SLOW = 50;
    private static final int RSI_N = 14, ATR_N = 14, ADX_N = 14, BB_N = 20;
    private static final double BB_K = 2.0;

    private static final BigDecimal ADX_MIN_TREND = bd("15");
    private static final BigDecimal RSI_HIGH = bd("55");
    private static final BigDecimal RSI_LOW = bd("45");
    private static final BigDecimal ATR_PCT_TWO_STEPS = bd("1.20");   // ≥1.2% → deeper OTM
    private static final BigDecimal IV_PCTILE_SPIKE = bd("95");       // IV%ile ≥95 → high
    private static final double SKEW_WIDE_ABS = 8.0;                   // |IV_PE - IV_CE| ≥ 8pp → wide

    // Δ target window for trend buys
    private static final double DELTA_MIN = 0.25, DELTA_MAX = 0.60;

    // PCR extremes
    private static final BigDecimal PCR_BULLISH_MAX = bd("0.80");
    private static final BigDecimal PCR_BEARISH_MIN = bd("1.20");

    // Exit hints (% of option price) + time-stop
    private static final BigDecimal STOP_PCT = bd("0.25");   // -25%
    private static final BigDecimal TARGET_PCT = bd("0.30"); // +30%
    private static final int TIME_STOP_MIN = 40;

    // Slippage/spread guard from live depth
    private static final BigDecimal MAX_SPREAD_PCT = bd("0.04"); // 4%

    // Score aggregator thresholds
    private static final int SCORE_T_LOW = 45;
    private static final int SCORE_T_HIGH = 65;

    // Dedupe + cooldowns
    private static final long DEDUPE_MINUTES = 3;
    private static final long REGIME_FLIP_COOLDOWN_MIN = 15;

    // Strike stepping
    private static final int STRIKE_STEP = 50;

    // Risk sizing (fallback until per-trade % is exposed in RiskSnapshot)
    private static final BigDecimal PER_TRADE_RISK_PCT_DEFAULT = new BigDecimal("5.0");

    // --- Strikes & volatility bands ---
    private static final int MAX_STRIKE_STEPS_FROM_ATM = 3;
    private static final BigDecimal QUIET_MAX_ATR_PCT = bd("0.30");
    private static final BigDecimal VOLATILE_MIN_ATR_PCT = bd("1.00");

    // --- Liquidity guard (hard filter) ---
    private static final BigDecimal MIN_LIQUIDITY_OI = bd("1000");
    private static final BigDecimal MAX_BIDASK_SPREAD_PCT = bd("0.03");

    // Metrics
    private final AtomicLong advicesCreated = new AtomicLong(0);
    private final AtomicLong advicesSkipped = new AtomicLong(0);
    private final AtomicLong advicesExecuted = new AtomicLong(0);

    @Autowired
    private DecisionService decisionService;
    @Autowired
    private MarketDataService marketDataService;
    @Autowired
    private OptionChainService optionChainService;
    @Autowired
    private UpstoxService upstoxService;
    @Autowired
    private AdviceService adviceService;
    @Autowired
    private RiskService riskService;
    @Autowired
    private OrdersService ordersService;
    @Autowired
    private TradesService tradesService;
    @Autowired
    private NewsService newsService;
    @Autowired
    private SentimentService sentimentService;
    @Autowired
    private StreamGateway stream;
    @Autowired(required = false)
    private EventPublisher bus;
    @Autowired
    private ObjectMapper mapper;

    private static List<String> safeList(List<String> a) {
        return a == null ? Collections.emptyList() : a;
    }

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String nz(BigDecimal b) {
        return b == null ? "—" : b.toString();
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static BigDecimal bd(int i) {
        return new BigDecimal(i);
    }

    private static BigDecimal bd(Double d) {
        return d != null ? BigDecimal.valueOf(d) : null;
    }

    // -------------------- Bias, legs, delta & exits --------------------

    private static BigDecimal val(Result<BigDecimal> r) {
        return (r != null && r.isOk()) ? r.get() : null;
    }

    private static double avg(Collection<Double> xs) {
        double s = 0;
        int n = 0;
        for (double d : xs) {
            s += d;
            n++;
        }
        return n == 0 ? Double.NaN : s / n;
    }

    private static double percentileRank(List<Double> data, double x) {
        List<Double> sorted = new ArrayList<Double>(data);
        Collections.sort(sorted);
        int count = 0;
        for (double v : sorted) if (v <= x) count++;
        return count / (double) sorted.size();
    }

    // -------------------- Public entrypoint --------------------
    public int generateAdvicesNow() {
        try {
            if (!riskService.hasHeadroom(1.0)) { // require at least 1% budget left (and not circuit-tripped)
                Map<String, Object> guard = new LinkedHashMap<>();
                guard.put("gate.risk", true);
                return skipTick(guard, "risk.block");
            }
        } catch (Exception e) {
            Map<String, Object> guard = new LinkedHashMap<>();
            guard.put("gate.risk.error", String.valueOf(e));
            return skipTick(guard, "risk.block.error");
        }
        int created = 0;
        final long t0 = System.currentTimeMillis();
        Map<String, Object> dbg = new LinkedHashMap<>();
        log.info("Strategy execution started");
        try {
            // 1) Decision trend + reasons
            Result<DecisionQuality> dqR = decisionService.getQuality();
            if (dqR == null || !dqR.isOk() || dqR.get() == null) {
                log.info("Strategy execution skipped - no valid decision quality available");
                return skipTick(dbg, "no-decision-quality");
            }
            DecisionQuality dq = dqR.get();
            String trend = up(dq.getTrend() == null ? "NEUTRAL" : dq.getTrend().name());
            List<String> baseReasons = safeList(dq.getReasons());

            // 2) Index LTP + nearest expiry & stale guard
            BigDecimal spot = getIndexLtp();
            if (spot == null) {
                log.info("Strategy execution skipped - no spot price available");
                return skipTick(dbg, "no-spot");
            }
            LocalDate expiry = nearestExpiry();
            if (expiry == null) {
                log.info("Strategy execution skipped - no valid expiry date available");
                return skipTick(dbg, "no-expiry");
            }

            // 3) Intraday candles (5m) with staleness check
            IntraDayCandleData c5 = candles(NIFTY, "minutes", "5");
            logCandleOrder("strategy.m5", c5);
            if (isStale(c5, 6)) {
                log.info("Strategy execution skipped - stale candle data");
                return skipTick(dbg, "stale-candles");
            }

            // 4) Indicators (5m)
            Ind ind5 = indicators(c5, spot);

            // Step-9: trend/momentum filters if enabled
            boolean adxWeak = ind5.adx != null && ind5.adx.compareTo(ADX_MIN_TREND) < 0;
            if (adxWeak) {
                log.info("Strategy execution stopped - ADX trend filter");
                return skipTick(dbg, "gate.trend.adx");
            }
            String eBias = emaBias(ind5);
            if ("FLAT".equals(eBias) || "NA".equals(eBias)) {
                log.info("Strategy execution stopped - momentum confirmation (EMA flat)");
                return skipTick(dbg, "gate.momentum.emaFlat");
            }

            // 5) Multi-timeframe alignment
            IntraDayCandleData c15 = candles(NIFTY, "minutes", "15");
            logCandleOrder("strategy.m15", c15);
            Ind ind15 = indicators(c15, spot);
            MarketRegime hrReg = marketDataService.getRegimeOn("minutes", "60").orElse(MarketRegime.NEUTRAL);

            adxWeak = ind5.adx != null && ind5.adx.compareTo(ADX_MIN_TREND) < 0;
            String effectiveTrend = adxWeak ? "NEUTRAL" : trend;

            boolean mtfAgree = emaBias(ind5).equals(emaBias(ind15)) && hrReg == mapToRegime(effectiveTrend);
            dbg.put("mtfAgree", mtfAgree);

            // 6) Structure filters
            Structure struct = structure(c5);
            PDRange pd = marketDataService.getPreviousDayRange(NIFTY).orElse(null);
            boolean breakoutOk = isBreakoutWithTrend(struct, effectiveTrend, pd);
            boolean vwapPullbackOk = isVwapPullbackWithTrend(ind5, effectiveTrend);
            if (!breakoutOk && !vwapPullbackOk) {
                dbg.put("gate.structure.breakoutOk", breakoutOk);
                dbg.put("gate.structure.vwapPullbackOk", vwapPullbackOk);
            }

            // 7) News & sentiment gates
            boolean newsBlock = newsBurstBlock(10); // last 10 min burst?
            BigDecimal senti = marketSentiment();
            boolean sentiBlock = (senti != null && senti.compareTo(bd("-10")) < 0);
            if (newsBlock || sentiBlock) {
                dbg.put("gate.news", newsBlock);
                dbg.put("gate.sentiment", senti);
                return skipTick(dbg, "news/sentiment");
            }

            // 8) Option-chain intelligence (PCR / OIΔ / IV, IV%ile, skew)
            Pcr pcr = pcr(expiry);
            IvStats ivStats = ivStatsNearAtm(expiry, spot);
            OiDelta oiDelta = oiDeltaTrend(expiry);

            boolean supplyPressureCE = oiDelta != null && oiDelta.ceRising && oiDelta.priceFalling;
            boolean supplyPressurePE = oiDelta != null && oiDelta.peRising && oiDelta.priceRising;

            // Set OTM distance by volatility (ATR%) and IV/skew
            int stepsOut = (ind5.atrPct != null && ind5.atrPct.compareTo(ATR_PCT_TWO_STEPS) >= 0) ? 2 : 1;
            if (ivStats != null && (ivStats.highAvgIv || ivStats.wideSkew)) stepsOut = Math.max(1, stepsOut - 1);

            // 9) CE/PE bias (EMA/RSI) + PCR extremes collapse to single-leg
            Bias emaRsiBias = biasFromEmaRsi(ind5);
            Bias pcrBias = (pcr == null) ? Bias.BOTH : pcr.toBias();
            Bias merged = mergeBias(mapTrendBias(effectiveTrend), emaRsiBias, pcrBias);

            // Step-9: if PCR_TILT flag is on and PCR is decisive, prefer it
            merged = pcrBias;
            log.info("PCR_TILT active → using PCR side: {}", merged);

            // Supply-pressure enforcement
            if (supplyPressureCE) {
                if (merged == Bias.CALL) {
                    return skipTick(dbg, "supply.CE");
                }
                if (merged == Bias.BOTH) merged = Bias.PUT;
            }
            if (supplyPressurePE) {
                if (merged == Bias.PUT) {
                    return skipTick(dbg, "supply.PE");
                }
                if (merged == Bias.BOTH) merged = Bias.CALL;
            }

            // 10) Time-of-day & expiry-day rules
            DayOfWeek dow = LocalDate.now(ZoneId.of("Asia/Kolkata")).getDayOfWeek();
            boolean expiryEve = dow == DayOfWeek.THURSDAY || dow == DayOfWeek.FRIDAY;
            if (expiryEve) stepsOut = Math.min(stepsOut, 1);

            // 11) Risk snapshot for budget-true sizing
            Result<RiskSnapshot> riskRes = riskService.getSummary();
            RiskSnapshot risk = (riskRes != null && riskRes.isOk()) ? riskRes.get() : null;
            int lotsCap = (risk == null || risk.getLotsCap() == null) ? 1 : risk.getLotsCap();
            int lotsUsed = (risk == null || risk.getLotsUsed() == null) ? 0 : risk.getLotsUsed();
            int freeLots = Math.max(0, lotsCap - lotsUsed);

            // 12) Portfolio overlap throttle
            PortfolioSide posSide = portfolioSide();
            if (posSide == PortfolioSide.HAVE_CALL && merged == Bias.CALL) {
                dbg.put("gate.overlap", "CALL");
                emitDebug(dbg, "skip.same-side-overlap");
                return 0;
            }
            if (posSide == PortfolioSide.HAVE_PUT && merged == Bias.PUT) {
                dbg.put("gate.overlap", "PUT");
                emitDebug(dbg, "skip.same-side-overlap");
                return 0;
            }

            // 13) Score aggregator → fire / skip
            Score score = scoreAll(trend, ind5, pcr, ivStats, mtfAgree, senti);
            dbg.put("score", score.total);
            if (score.total < SCORE_T_LOW) return skipTick(dbg, "score.low");
            if (!mtfAgree && score.total < SCORE_T_HIGH) return skipTick(dbg, "score.mid.mtf_disagree");

            // 14) Regime flip cooldown & drawdown brakes
            if (recentRegimeFlip()) return skipTick(dbg, "cooldown.regimeflip");
            if (riskService.isDailyCircuitTripped().orElse(false)) return skipTick(dbg, "gate.circuit");

            // NEW: service-grade skew near ATM
            Result<BigDecimal> skewRes = optionChainService.getIvSkew(NIFTY, expiry, spot);
            boolean wideSkewSvc = (skewRes != null && skewRes.isOk() && skewRes.get() != null
                    && skewRes.get().abs().compareTo(bd(SKEW_WIDE_ABS)) >= 0);
            if (wideSkewSvc) stepsOut = Math.max(1, stepsOut - 1);

            // 15) Build candidate legs (with Δ targeting & slippage guard)
            BigDecimal atm = optionChainService.computeAtmStrike(spot, STRIKE_STEP);
            List<LegSpec> legs = chooseLegs(merged, expiry, atm, stepsOut, ind5.atrPct);
            if (legs.isEmpty()) return skipTick(dbg, "no-legs");

            // Respect Step-9 toggles first for BOTH bias
            if (merged == Bias.BOTH) {
                legs.clear();
                BigDecimal ce = atm.add(bd(STRIKE_STEP));
                BigDecimal pe = atm.subtract(bd(STRIKE_STEP));
                legs.add(new LegSpec(expiry, ce, OptionType.CALL));
                legs.add(new LegSpec(expiry, pe, OptionType.PUT));
            }

            // per-side OI-Δ leaders
            Result<LinkedHashMap<Integer, Long>> topCe = optionChainService.topOiChange(NIFTY, expiry, OptionType.CALL, 5);
            Result<LinkedHashMap<Integer, Long>> topPe = optionChainService.topOiChange(NIFTY, expiry, OptionType.PUT, 5);
            legs = nudgeByTopOi(legs, topCe, topPe);

            // filter by Δ window and slippage/spread
            List<LegSpec> filtered = new ArrayList<LegSpec>();
            for (LegSpec L : legs) {
                InstrumentData inst = findContract(L);
                if (inst == null) {
                    incLegSkipped("noContract", null);
                    continue;
                }

                if (!passesLiquidity(inst)) {
                    incLegSkipped("liquidity", inst.getInstrumentKey());
                    continue;
                }

                Result<BigDecimal> ivPct = optionChainService.getIvPercentile(
                        NIFTY, expiry, bd(inst.getStrikePrice()), typeOf(inst));

                if (ivPct != null && ivPct.isOk() && ivPct.get() != null
                        && ivPct.get().compareTo(IV_PCTILE_SPIKE) >= 0) {
                    incLegSkipped("ivPctile", inst.getInstrumentKey());
                    continue;
                }
                if (!deltaInRange(inst.getInstrumentKey())) {
                    incLegSkipped("delta", inst.getInstrumentKey());
                    continue;
                }
                if (!ordersService.preflightSlippageGuard(inst.getInstrumentKey(), MAX_SPREAD_PCT)) {
                    incLegSkipped("slippage", inst.getInstrumentKey());
                    continue;
                }
                filtered.add(new LegSpec(L.expiry, bd(inst.getStrikePrice()), typeOf(inst)));
            }

            if (filtered.isEmpty()) return skipTick(dbg, "no-legs.after-filters");

            // 16) Persist advices (dedupe, exit plan, observability)
            List<LegSpec> ranked = rankByTightestSpread(filtered);
            int createdNow = 0;
            for (LegSpec L : ranked) {
                InstrumentData inst = findContract(L);
                if (inst == null) continue;

                String human = humanSymbol(inst);
                if (isDuplicateInWindow(inst.getInstrumentKey(), "BUY")) {
                    incLegSkipped("dedupe", inst.getInstrumentKey());
                    continue;
                }

                int lotSize = Math.max(1, inst.getLotSize().intValue());

                double optLtp = 0.0;
                try {
                    GetMarketQuoteLastTradedPriceResponseV3 q = upstoxService.getMarketLTPQuote(inst.getInstrumentKey());
                    if (q != null && q.getData() != null && q.getData().get(inst.getInstrumentKey()) != null) {
                        optLtp = q.getData().get(inst.getInstrumentKey()).getLastPrice();
                    }
                } catch (Exception ignore) {
                }

                Result<RiskSnapshot> riskRes2 = riskService.getSummary();
                RiskSnapshot risk2 = (riskRes2 != null && riskRes2.isOk()) ? riskRes2.get() : null;

                int freeLots2 = freeLots;
                int lotsForThisLeg = dynamicLots(ind5.atrPct, optLtp, lotSize, risk2, freeLots2);
                if (lotsForThisLeg < 1) {
                    incLegSkipped("zeroLots", inst.getInstrumentKey());
                    continue;
                }
                int qty = lotSize * lotsForThisLeg;
                freeLots = Math.max(0, freeLots - lotsForThisLeg);

                ExitHints x = exitHints(inst.getInstrumentKey());

                List<String> rs = new ArrayList<String>(baseReasons);
                rs.add(taSummary(ind5));
                if (pcr != null) rs.add(pcr.toReason());
                if (ivStats != null) rs.add(ivStats.toReason());
                if (oiDelta != null) rs.add(oiDelta.toReason());
                rs.add("Score=" + score.total + " (" + score.breakdown + ")");
                rs.add("MTF=" + (mtfAgree ? "AGREE" : "DISAGREE") + ", Reg=" + hrReg);
                rs.add("EXIT: SL=" + (x == null ? "—" : x.sl) + ", TP=" + (x == null ? "—" : x.tp) + ", TTL=" + TIME_STOP_MIN + "m");

                Advice a = new Advice();
                a.setSymbol(human);
                a.setInstrument_token(inst.getInstrumentKey());
                a.setTransaction_type("BUY");
                a.setOrder_type("MARKET");
                a.setProduct("MIS");
                a.setValidity("DAY");
                a.setQuantity(qty);
                a.setReason(joinReasons(rs));
                a.setStatus(AdviceStatus.PENDING);
                a.setCreatedAt(Instant.now());
                a.setUpdatedAt(Instant.now());

                Result<Advice> saved = adviceService.create(a);
                if (saved != null && saved.isOk() && saved.get() != null) {
                    createdNow++;
                    advicesCreated.incrementAndGet();
                    log.info("Created advice: {} qty={} (ik={}, expiry={}, strike={}, type={})",
                            human, qty, inst.getInstrumentKey(), L.expiry, L.strike, L.type);
                    dbg.put("advice", human);
                    dbg.put("qty", qty);
                    emitDebug(dbg, "advice.created");
                }
            }
            created += createdNow;
            return created;
        } catch (Exception t) {
            log.error("strategy: failure {}", t.getMessage(), t);
            return created;
        } finally {
            long ms = System.currentTimeMillis() - t0;
            if (ms > 1000) log.info("strategy: run took {} ms", ms);
            emitMetrics(true);
        }
    }

    // NEW: OI-liquidity + spread filter
    private boolean passesLiquidity(InstrumentData inst) {
        try {
            if (inst == null) return false;
            String ik = inst.getInstrumentKey();

            MarketQuoteOptionGreekV3 g = optionChainService.getGreek(ik).orElse(null);
            if (g == null) return true; // if unknown, don't block

            long oi = Math.max(0L, g.getOi().longValue());
            if (oi < MIN_LIQUIDITY_OI.longValue()) return false;

            // spread% guard (reuses OrdersService depth)
            BigDecimal spread = ordersService.getSpreadPct(ik).orElse(null);
            return spread == null || spread.compareTo(MAX_BIDASK_SPREAD_PCT) <= 0;
        } catch (Exception t) {
            log.error("Liquidity check failed: {}", t);
            return true; // be permissive on errors
        }
    }

    private Score scoreAll(String trend, Ind ind, Pcr pcr, IvStats iv, boolean mtfAgree, BigDecimal sentiment) {
        int sTrend = ("BULLISH".equals(trend) || "UPTREND".equals(trend) || "BEARISH".equals(trend) || "DOWNTREND".equals(trend)) ? 40 : 20;
        int sEmaRsi = emaRsiPoints(ind);
        int sAdx = (ind.adx != null) ? clamp(ind.adx.intValue(), 0, 25) / 2 : 5;
        int sChain = (pcr == null ? 10 : pcr.points()) + (iv == null ? 0 : iv.points());
        int sMtf = mtfAgree ? 10 : 0;
        int sSent = sentiment == null ? 5 : clamp(sentiment.intValue() + 50, 0, 100) / 20; // -50..+50 → 0..5
        int total = sTrend + sEmaRsi + sAdx + sChain + sMtf + sSent;
        return new Score(total, "trend=" + sTrend + ", ema/rsi=" + sEmaRsi + ", adx=" + sAdx + ", chain=" + sChain + ", mtf=" + sMtf + ", sent=" + sSent);
    }

    private int emaRsiPoints(Ind ind) {
        int pts = 0;
        Bias b = biasFromEmaRsi(ind);
        if (b == Bias.CALL || b == Bias.PUT) pts += 10;
        else pts += 5;
        if (ind.rsi != null) {
            if (ind.rsi.compareTo(RSI_HIGH) > 0 || ind.rsi.compareTo(RSI_LOW) < 0) pts += 5;
        }
        return pts;
    }

    // -------------------- Option-chain intelligence --------------------

    // Run periodically (kept short)
    @Scheduled(fixedDelayString = "${trade.strategy.refresh-ms:20000}")
    public void tick() {
        generateAdvicesNow();
    }

    private String joinReasons(List<String> rs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rs.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append(rs.get(i));
        }
        return sb.toString();
    }

    private boolean recentRegimeFlip() {
        try {
            Optional<MarketRegime> r1 = marketDataService.getRegimeOn("minutes", "60");
            Optional<MarketRegime> r2 = marketDataService.getRegimeOn("minutes", "60");
            if (!r1.isPresent() || !r2.isPresent()) return false;
            if (r1.get() != r2.get()) {
                Instant lastFlip = marketDataService.getLastRegimeFlipInstant().orElse(Instant.EPOCH);
                return Duration.between(lastFlip, Instant.now()).toMinutes() < REGIME_FLIP_COOLDOWN_MIN;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private Bias mapTrendBias(String t) {
        if ("BULLISH".equals(t) || "UPTREND".equals(t)) return Bias.CALL;
        if ("BEARISH".equals(t) || "DOWNTREND".equals(t)) return Bias.PUT;
        return Bias.BOTH;
    }

    private Bias biasFromEmaRsi(Ind i) {
        if (i == null) return Bias.BOTH;
        int s = 0;
        if (i.ema20 != null && i.ema50 != null) s += i.ema20.compareTo(i.ema50) > 0 ? 1 : -1;
        if (i.rsi != null) {
            if (i.rsi.compareTo(RSI_HIGH) > 0) s++;
            else if (i.rsi.compareTo(RSI_LOW) < 0) s--;
        }
        return s > 0 ? Bias.CALL : s < 0 ? Bias.PUT : Bias.BOTH;
    }

    private Bias mergeBias(Bias a, Bias b, Bias c) {
        int ce = 0, pe = 0;
        Bias[] arr = new Bias[]{a, b, c};
        for (Bias x : arr) {
            if (x == Bias.CALL) ce++;
            else if (x == Bias.PUT) pe++;
        }
        if (ce >= 2) return Bias.CALL;
        if (pe >= 2) return Bias.PUT;
        return Bias.BOTH;
    }

    // Build legs using ATR logic; Step-9 toggles handled earlier when bias==BOTH
    public List<LegSpec> chooseLegs(Bias b, LocalDate expiry, BigDecimal atm, int stepsOut, BigDecimal atrPct) {
        List<LegSpec> out = new ArrayList<LegSpec>();
        if (expiry == null || atm == null) return out;

        boolean isQuiet = (atrPct != null && atrPct.compareTo(QUIET_MAX_ATR_PCT) <= 0);
        boolean isVolatile = (atrPct != null && atrPct.compareTo(VOLATILE_MIN_ATR_PCT) >= 0);

        if (b == Bias.BOTH) {
            if (isQuiet) {
                out.add(new LegSpec(expiry, atm, OptionType.CALL));
                out.add(new LegSpec(expiry, atm, OptionType.PUT));
                return out;
            } else if (isVolatile) {
                BigDecimal ce = atm.add(bd(STRIKE_STEP));
                BigDecimal pe = atm.subtract(bd(STRIKE_STEP));
                out.add(new LegSpec(expiry, ce, OptionType.CALL));
                out.add(new LegSpec(expiry, pe, OptionType.PUT));
                return out;
            }
            BigDecimal up = atm.add(bd(STRIKE_STEP * Math.max(0, stepsOut)));
            BigDecimal dn = atm.subtract(bd(STRIKE_STEP * Math.max(0, stepsOut)));
            out.add(new LegSpec(expiry, up, OptionType.CALL));
            out.add(new LegSpec(expiry, dn, OptionType.PUT));
            return out;
        }

        int maxSteps = Math.max(0, Math.min(MAX_STRIKE_STEPS_FROM_ATM, Math.max(stepsOut, 1)));
        for (int i = 0; i <= maxSteps; i++) {
            BigDecimal strike = (b == Bias.CALL)
                    ? atm.add(bd(STRIKE_STEP * i))
                    : atm.subtract(bd(STRIKE_STEP * i));
            out.add(new LegSpec(expiry, strike, (b == Bias.CALL) ? OptionType.CALL : OptionType.PUT));
        }
        return out;
    }

    private boolean deltaInRange(String instrumentKey) {
        try {
            MarketQuoteOptionGreekV3 g = optionChainService.getGreek(instrumentKey).orElse(null);
            if (g == null) return true; // allow when greek unavailable
            double d = Math.abs(g.getDelta());
            return d >= DELTA_MIN && d <= DELTA_MAX;
        } catch (Exception t) {
            return false;
        }
    }

    private ExitHints exitHints(String instrumentKey) {
        try {
            Optional<BigDecimal> midOpt = ordersService.getBidAskMid(instrumentKey);
            BigDecimal px = midOpt.orElseGet(() -> {
                try {
                    GetMarketQuoteLastTradedPriceResponseV3 q = upstoxService.getMarketLTPQuote(instrumentKey);
                    double ltp = (q != null && q.getData() != null && q.getData().get(instrumentKey) != null)
                            ? q.getData().get(instrumentKey).getLastPrice() : 0.0;
                    return ltp > 0 ? BigDecimal.valueOf(ltp) : null;
                } catch (Exception t) {
                    return null;
                }
            });
            if (px == null || px.compareTo(BigDecimal.ZERO) <= 0) return null;

            ExitHints x = new ExitHints();
            x.sl = px.subtract(px.multiply(STOP_PCT)).setScale(2, RoundingMode.HALF_UP);
            x.tp = px.add(px.multiply(TARGET_PCT)).setScale(2, RoundingMode.HALF_UP);
            return x;
        } catch (Exception t) {
            return null;
        }
    }

    private Pcr pcr(LocalDate expiry) {
        try {
            Result<BigDecimal> r1 = optionChainService.getOiPcr(NIFTY, expiry);
            Result<BigDecimal> r2 = optionChainService.getVolumePcr(NIFTY, expiry);
            return new Pcr(val(r1), val(r2));
        } catch (Exception t) {
            return null;
        }
    }

    private IvStats ivStatsNearAtm(LocalDate expiry, BigDecimal spot) {
        try {
            BigDecimal atm = optionChainService.computeAtmStrike(spot, STRIKE_STEP);
            Result<List<InstrumentData>> rng =
                    optionChainService.listContractsByStrikeRange(NIFTY, expiry, atm.subtract(bd(100)), atm.add(bd(100)));

            Result<Map<String, MarketQuoteOptionGreekV3>> gRes =
                    optionChainService.getGreeksForExpiry(NIFTY, expiry);
            Map<String, MarketQuoteOptionGreekV3> greeks =
                    (gRes != null && gRes.isOk() && gRes.get() != null) ? gRes.get() : new HashMap<String, MarketQuoteOptionGreekV3>();

            List<Double> ceIv = new ArrayList<Double>(), peIv = new ArrayList<Double>();
            List<Double> allIv = new ArrayList<Double>();
            if (rng != null && rng.isOk() && rng.get() != null) {
                for (InstrumentData oi : rng.get()) {
                    MarketQuoteOptionGreekV3 g = greeks.get(oi.getInstrumentKey());
                    Double iv = (g == null ? null : g.getIv());
                    if (iv == null) continue;
                    allIv.add(iv);
                    if ("CE".equalsIgnoreCase(oi.getUnderlyingType())) ceIv.add(iv);
                    else if ("PE".equalsIgnoreCase(oi.getUnderlyingType())) peIv.add(iv);
                }
            }
            if (allIv.isEmpty() || ceIv.isEmpty() || peIv.isEmpty()) return null;

            double avgIv = avg(allIv);
            double ceAvg = avg(ceIv);
            double peAvg = avg(peIv);
            double skewAbs = Math.abs(peAvg - ceAvg);
            double pctile = percentileRank(allIv, avgIv);

            IvStats s = new IvStats();
            s.avgIv = bd(avgIv).setScale(2, RoundingMode.HALF_UP);
            s.ceAvg = bd(ceAvg).setScale(2, RoundingMode.HALF_UP);
            s.peAvg = bd(peAvg).setScale(2, RoundingMode.HALF_UP);
            s.skewAbs = skewAbs;
            s.ivPctile = bd(pctile * 100).setScale(0, RoundingMode.HALF_UP);
            s.highAvgIv = s.ivPctile.compareTo(IV_PCTILE_SPIKE) >= 0;
            s.wideSkew = skewAbs >= SKEW_WIDE_ABS;
            return s;
        } catch (Exception t) {
            return null;
        }
    }

    private OiDelta oiDeltaTrend(LocalDate expiry) {
        try {
            OptionChainService.OiSnapshot prev = optionChainService.getLatestOiSnapshot(NIFTY, expiry, -1).orElse(null);
            OptionChainService.OiSnapshot curr = optionChainService.getLatestOiSnapshot(NIFTY, expiry, 0).orElse(null);
            if (prev == null || curr == null) return null;

            BigDecimal ceDelta = curr.totalCeOi().subtract(prev.totalCeOi());
            BigDecimal peDelta = curr.totalPeOi().subtract(prev.totalPeOi());

            IntraDayCandleData c5 = candles(NIFTY, "minutes", "5");
            logCandleOrder("strategy.m5", c5);
            if (c5 == null || c5.getCandles().size() < 2) return null;
            double c1 = ((Number) c5.getCandles().get(c5.getCandles().size() - 2).get(4)).doubleValue();
            double c2 = ((Number) c5.getCandles().get(c5.getCandles().size() - 1).get(4)).doubleValue();

            OiDelta d = new OiDelta();
            d.ceRising = ceDelta != null && ceDelta.compareTo(BigDecimal.ZERO) > 0;
            d.peRising = peDelta != null && peDelta.compareTo(BigDecimal.ZERO) > 0;
            d.priceFalling = c2 < c1;
            d.priceRising = c2 > c1;
            d.ceDelta = ceDelta;
            d.peDelta = peDelta;
            return d;
        } catch (Exception t) {
            return null;
        }
    }

    // -------------------- Structure filters --------------------
    private Structure structure(IntraDayCandleData cs) {
        Structure s = new Structure();
        if (cs == null) return s;

        List<List<Object>> candles = cs.getCandles();
        if (candles == null || candles.size() < 25) return s;

        int start = candles.size() - BB_N;
        double hi = -1e9, lo = 1e9;
        for (int i = start; i < candles.size(); i++) {
            hi = Math.max(hi, ((Number) candles.get(i).get(2)).doubleValue());
            lo = Math.min(lo, ((Number) candles.get(i).get(3)).doubleValue());
        }
        s.donHi = bd(hi);
        s.donLo = bd(lo);

        double pv = 0, vv = 0;
        for (List<Object> candle : candles) {
            double close = ((Number) candle.get(4)).doubleValue();
            double volume = ((Number) candle.get(5)).doubleValue();
            pv += close * volume;
            vv += volume;
        }
        if (vv > 0) s.vwap = bd(pv / vv);
        s.lastClose = bd(((Number) candles.get(candles.size() - 1).get(4)).doubleValue());
        return s;
    }

    private boolean isBreakoutWithTrend(Structure st, String trend, PDRange pd) {
        if (st == null || st.donHi == null || st.donLo == null || st.lastClose == null) return false;
        if ("BULLISH".equals(trend) || "UPTREND".equals(trend)) {
            boolean b = st.lastClose.compareTo(st.donHi) >= 0;
            if (pd != null) b &= st.lastClose.compareTo(pd.pdh) >= 0;
            return b;
        }
        if ("BEARISH".equals(trend) || "DOWNTREND".equals(trend)) {
            boolean b = st.lastClose.compareTo(st.donLo) <= 0;
            if (pd != null) b &= st.lastClose.compareTo(pd.pdl) <= 0;
            return b;
        }
        return false;
    }

    private boolean isVwapPullbackWithTrend(Ind ind, String trend) {
        if (ind == null || ind.vwap == null || ind.close == null) return false;
        if ("BULLISH".equals(trend) || "UPTREND".equals(trend)) {
            return ind.close.compareTo(ind.vwap) >= 0 && ind.ema20.compareTo(ind.ema50) > 0;
        }
        if ("BEARISH".equals(trend) || "DOWNTREND".equals(trend)) {
            return ind.close.compareTo(ind.vwap) <= 0 && ind.ema20.compareTo(ind.ema50) < 0;
        }
        return false;
    }

    // -------------------- Indicators & data --------------------
    public Ind indicators(IntraDayCandleData cs, BigDecimal spot) {
        Ind out = new Ind();
        try {
            if (cs == null || cs.getCandles() == null || cs.getCandles().size() < 10) return out;

            Duration timeframe = inferTimeframe(cs);
            BarSeries series = toBarSeries(cs, timeframe);
            int minBars = Math.max(EMA_SLOW, Math.max(RSI_N, Math.max(ATR_N, ADX_N)));
            if (series == null || series.getBarCount() < minBars) {
                out.ema20 = out.ema50 = out.rsi = out.adx = out.atr = out.atrPct = null;
                return out;
            }

            final int end = series.getEndIndex();

            ClosePriceIndicator close = new ClosePriceIndicator(series);
            EMAIndicator emaFastInd = new EMAIndicator(close, EMA_FAST);
            EMAIndicator emaSlowInd = new EMAIndicator(close, EMA_SLOW);
            RSIIndicator rsiInd = new RSIIndicator(close, RSI_N);
            ADXIndicator adxInd = new ADXIndicator(series, ADX_N);
            ATRIndicator atrInd = new ATRIndicator(series, ATR_N);

            SMAIndicator sma = new SMAIndicator(close, BB_N);
            StandardDeviationIndicator stdev = new StandardDeviationIndicator(close, BB_N);
            double mid = sma.getValue(end).doubleValue();
            double sd = stdev.getValue(end).doubleValue();
            double bbU = mid + (BB_K * sd);
            double bbL = mid - (BB_K * sd);

            VWAPIndicator vwapInd = new VWAPIndicator(series, 30);

            out.ema20 = bd(emaFastInd.getValue(end).doubleValue());
            out.ema50 = bd(emaSlowInd.getValue(end).doubleValue());
            out.rsi = bd(rsiInd.getValue(end).doubleValue());
            out.adx = bd(adxInd.getValue(end).doubleValue());
            out.atr = bd(atrInd.getValue(end).doubleValue());
            out.bbU = bd(bbU);
            out.bbL = bd(bbL);
            out.vwap = bd(vwapInd.getValue(end).doubleValue());
            out.close = bd(close.getValue(end).doubleValue());

            if (out.atr != null && spot != null && spot.compareTo(BigDecimal.ZERO) > 0) {
                out.atrPct = out.atr.multiply(bd(100)).divide(spot, 2, java.math.RoundingMode.HALF_UP);
            }
        } catch (Throwable t) {
            log.warn("indicators(): ta4j computation failed, returning partial: {}", t.toString());
        }
        return out;
    }

    private BarSeries toBarSeries(IntraDayCandleData cs, Duration timeframe) {
        try {
            BarSeries series = new BaseBarSeriesBuilder().withName("NIFTY-" + timeframe).build();
            ZoneId zone = ZoneId.of("Asia/Kolkata");
            List<List<Object>> rows = cs.getCandles();
            if (rows == null || rows.isEmpty()) return series;

            // ---- enforce chronological order (oldest -> newest)
            rows.sort((a, b) -> {
                long ta = toEpochMillis(a.get(0));
                long tb = toEpochMillis(b.get(0));
                return Long.compare(ta, tb);
            });

            for (List<Object> row : rows) {
                if (row == null || row.size() < 6) continue;
                long epochMs = toEpochMillis(row.get(0));
                if (epochMs <= 0L) continue;

                ZonedDateTime endZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone);
                double open = row.get(1) instanceof Number ? ((Number) row.get(1)).doubleValue() : 0d;
                double high = row.get(2) instanceof Number ? ((Number) row.get(2)).doubleValue() : open;
                double low = row.get(3) instanceof Number ? ((Number) row.get(3)).doubleValue() : open;
                double close = row.get(4) instanceof Number ? ((Number) row.get(4)).doubleValue() : open;
                double vol = row.get(5) instanceof Number ? ((Number) row.get(5)).doubleValue() : 0d;

                series.addBar(new BaseBar(timeframe, endZdt, open, high, low, close, vol));
            }
            return series;
        } catch (Throwable t) {
            log.warn("toBarSeries(): failed to convert candles to BarSeries: {}", t.toString());
            return null;
        }
    }

    // StrategyService.java
// ...
    private void logCandleOrder(String label, IntraDayCandleData cs) {
        try {
            if (cs == null || cs.getCandles() == null || cs.getCandles().size() < 2) return;
            long t0 = toEpochMillis(cs.getCandles().get(0).get(0));
            long t1 = toEpochMillis(cs.getCandles().get(1).get(0));
            if (t0 > t1) {
                log.warn("{} candles appear NEWEST-first. Sorting to chronological.", label);
            }
        } catch (Throwable ignore) { /* best-effort */ }
    }

    // If you don't already have this helper here:
    private long toEpochMillis(Object ts) {
        if (ts instanceof Number) {
            long v = ((Number) ts).longValue();
            return (v > 3_000_000_000L) ? v : v * 1000L; // seconds→ms if needed
        }
        if (ts instanceof String s) {
            long v = Long.parseLong(s.trim());
            return (v > 3_000_000_000L) ? v : v * 1000L;
        }
        return -1L;
    }

    private Duration inferTimeframe(IntraDayCandleData cs) {
        try {
            List<List<Object>> rows = cs.getCandles();
            int n = rows == null ? 0 : rows.size();
            if (n >= 2) {
                long t1 = toEpochMillis(rows.get(n - 2).get(0));
                long t2 = toEpochMillis(rows.get(n - 1).get(0));
                long diffMs = Math.abs(t2 - t1);
                if (diffMs >= 60_000L && diffMs <= 4L * 60L * 60L * 1000L) {
                    return Duration.ofMillis(diffMs);
                }
            }
        } catch (Throwable ignore) {
        }
        return Duration.ofMinutes(5);
    }

    private IntraDayCandleData candles(String key, String unit, String interval) {
        try {
            GetIntraDayCandleResponse ic = upstoxService.getIntradayCandleData(key, unit, interval);
            return ic == null ? null : ic.getData();
        } catch (Exception t) {
            return null;
        }
    }

    // replace your current isStale(..)
    private boolean isStale(IntraDayCandleData cs, int maxLagMin) {
        try {
            if (cs == null || cs.getCandles() == null || cs.getCandles().isEmpty()) return true;

            long maxEpochSec = Long.MIN_VALUE;
            for (List<Object> row : cs.getCandles()) {
                if (row == null || row.isEmpty()) continue;
                Object t = row.get(0);
                long v;
                if (t instanceof Number) {
                    v = ((Number) t).longValue();
                } else if (t instanceof String s) {
                    v = Long.parseLong(s.trim());
                } else {
                    continue;
                }
                // Upstox sometimes sends seconds; sometimes millis in other contexts. Normalize to seconds.
                if (v > 3_000_000_000L) v = v / 1000L;
                if (v > maxEpochSec) maxEpochSec = v;
            }
            if (maxEpochSec <= 0L) return true;
            Instant last = Instant.ofEpochSecond(maxEpochSec);
            return Duration.between(last, Instant.now()).toMinutes() > maxLagMin;
        } catch (Exception t) {
            return false;
        }
    }

    private BigDecimal getIndexLtp() {
        try {
            Result<BigDecimal> r = marketDataService.getLtp(NIFTY);
            return (r != null && r.isOk()) ? r.get() : null;
        } catch (Exception t) {
            return null;
        }
    }

    private LocalDate nearestExpiry() {
        try {
            Result<List<LocalDate>> r = optionChainService.listNearestExpiries(NIFTY, 1);
            return (r != null && r.isOk() && r.get() != null && !r.get().isEmpty()) ? r.get().get(0) : null;
        } catch (Exception t) {
            return null;
        }
    }

    // -------------------- News & sentiment --------------------
    private boolean newsBurstBlock(int minutes) {
        try {
            Integer burst = newsService.getRecentBurstCount(minutes).orElse(0);
            return burst != null && burst >= 5;
        } catch (Exception t) {
            return false;
        }
    }

    private BigDecimal marketSentiment() {
        try {
            return sentimentService.getMarketSentimentScore().orElse(null);
        } catch (Exception t) {
            return null;
        }
    }

    // Budget-true, volatility-scaled lots (returns 0 or 1 for now)
    private int dynamicLots(BigDecimal atrPct,
                            double optionLtp,
                            int lotSize,
                            RiskSnapshot risk,
                            int freeLots) {
        try {
            if (risk == null || freeLots <= 0) return 0;
            if (atrPct != null && atrPct.compareTo(new BigDecimal("2.50")) > 0) return 0;

            BigDecimal budgetLeft;
            try {
                budgetLeft = BigDecimal.valueOf(risk.getRiskBudgetLeft());
            } catch (Exception e) {
                budgetLeft = BigDecimal.ZERO;
            }

            if (budgetLeft.compareTo(BigDecimal.ZERO) <= 0 || optionLtp <= 0 || lotSize <= 0) return 0;

            BigDecimal budgetPerTrade = budgetLeft.multiply(PER_TRADE_RISK_PCT_DEFAULT)
                    .divide(new BigDecimal("100"), 2, RoundingMode.DOWN);

            BigDecimal costPerLot = BigDecimal.valueOf(optionLtp).multiply(BigDecimal.valueOf(lotSize));
            if (costPerLot.compareTo(BigDecimal.ZERO) <= 0) return 0;

            int raw = budgetPerTrade.divide(costPerLot, 0, RoundingMode.FLOOR).intValue();
            return Math.max(0, Math.min(raw, Math.min(freeLots, 1)));
        } catch (Exception t) {
            return 0;
        }
    }

    private PortfolioSide portfolioSide() {
        try {
            return tradesService.getOpenPortfolioSide().orElse(PortfolioSide.NONE);
        } catch (Exception t) {
            return PortfolioSide.NONE;
        }
    }

    // -------------------- Small helpers --------------------
    private InstrumentData findContract(LegSpec L) {
        try {
            Result<InstrumentData> r =
                    optionChainService.findContract(NIFTY, L.expiry, L.strike, L.type);
            return (r != null && r.isOk()) ? r.get() : null;
        } catch (Exception t) {
            return null;
        }
    }

    private String humanSymbol(InstrumentData oi) {
        String side = typeOf(oi).name();
        return "NIFTY " + oi.getStrikePrice() + " " + side;
    }

    private OptionType typeOf(InstrumentData oi) {
        String t = oi.getUnderlyingType() == null ? "" : oi.getUnderlyingType().toUpperCase(Locale.ROOT);
        return "CE".equals(t) ? OptionType.CALL : OptionType.PUT;
    }

    private String taSummary(Ind i) {
        String ema = (i.ema20 != null && i.ema50 != null) ? (i.ema20.compareTo(i.ema50) >= 0 ? "EMA20>EMA50" : "EMA20<EMA50") : "EMA—";
        return "TA:" + ema
                + ", RSI=" + nz(i.rsi) + ", ADX=" + nz(i.adx) + ", ATR%=" + nz(i.atrPct)
                + ", VWAP=" + nz(i.vwap);
    }

    private MarketRegime mapToRegime(String trend) {
        if ("BULLISH".equals(trend) || "UPTREND".equals(trend)) return MarketRegime.BULLISH;
        if ("BEARISH".equals(trend) || "DOWNTREND".equals(trend)) return MarketRegime.BEARISH;
        return MarketRegime.NEUTRAL;
    }

    private void emitDebug(Map<String, Object> base, String msg) {
        Map<String, Object> p = new LinkedHashMap<String, Object>(base);
        p.put("msg", msg);
        p.put("ts", Instant.now());
        try {
            JsonNode node = mapper.valueToTree(p);
            stream.publishDecision("decision.debug", node.toPrettyString());
        } catch (Exception ignored) {
        }
    }

    private String emaBias(Ind ind) {
        if (ind == null || ind.ema20 == null || ind.ema50 == null) return "NA";
        int cmp = ind.ema20.compareTo(ind.ema50);
        if (cmp > 0) return "UP";
        if (cmp < 0) return "DOWN";
        return "FLAT";
    }

    private List<LegSpec> nudgeByTopOi(List<LegSpec> legs,
                                       Result<LinkedHashMap<Integer, Long>> topCe,
                                       Result<LinkedHashMap<Integer, Long>> topPe) {
        if (legs == null || legs.isEmpty()) return legs;
        Set<Integer> ceHot = (topCe != null && topCe.isOk()) ? topCe.get().keySet() : Collections.emptySet();
        Set<Integer> peHot = (topPe != null && topPe.isOk()) ? topPe.get().keySet() : Collections.emptySet();

        List<LegSpec> out = new ArrayList<LegSpec>(legs.size());
        for (LegSpec L : legs) {
            Set<Integer> pool = (L.type == OptionType.CALL) ? ceHot : peHot;
            if (pool.isEmpty()) {
                out.add(L);
                continue;
            }

            int k = L.strike.intValue();
            int best = k;
            int bestDiff = Integer.MAX_VALUE;
            for (Integer s : pool) {
                int diff = Math.abs(s - k);
                if (diff < bestDiff && diff <= 100) {
                    best = s;
                    bestDiff = diff;
                }
            }
            if (best != k) {
                out.add(new LegSpec(L.expiry, bd(best), L.type));
            } else out.add(L);
        }
        return out;
    }

    private List<LegSpec> rankByTightestSpread(List<LegSpec> candidates) {
        record Row(LegSpec leg, BigDecimal spread) {
        }
        List<Row> rows = new ArrayList<Row>();
        for (LegSpec L : candidates) {
            InstrumentData inst = findContract(L);
            if (inst == null) continue;
            String ik = inst.getInstrumentKey();

            BigDecimal spreadPct = ordersService.getSpreadPct(ik).orElse(new BigDecimal("9E9"));
            rows.add(new Row(L, spreadPct));
        }

        Collections.sort(rows, new Comparator<Row>() {
            public int compare(Row a, Row b) {
                return a.spread.compareTo(b.spread);
            }
        });

        List<LegSpec> withinCap = new ArrayList<LegSpec>();
        for (Row r : rows) if (r.spread.compareTo(MAX_SPREAD_PCT) <= 0) withinCap.add(r.leg);
        if (!withinCap.isEmpty()) return withinCap;

        List<LegSpec> all = new ArrayList<LegSpec>(rows.size());
        for (Row r : rows) all.add(r.leg);
        return all;
    }

    private boolean isDuplicateInWindow(final String instrumentKey, final String txType) {
        try {
            Result<List<Advice>> res = adviceService.list();
            if (res == null || !res.isOk() || res.get() == null) return false;

            final Instant threshold = Instant.now().minus(DEDUPE_MINUTES, ChronoUnit.MINUTES);
            final EnumSet<AdviceStatus> windowStatuses =
                    EnumSet.of(AdviceStatus.PENDING, AdviceStatus.EXECUTED);

            for (Advice a : res.get()) {
                if (a == null) continue;
                if (a.getInstrument_token() == null || a.getStatus() == null || a.getCreatedAt() == null) continue;

                String tx = a.getTransaction_type() == null ? "" : a.getTransaction_type();
                if (instrumentKey.equals(a.getInstrument_token())
                        && windowStatuses.contains(a.getStatus())
                        && txType.equalsIgnoreCase(tx)
                        && a.getCreatedAt().isAfter(threshold)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private int skipTick(Map<String, Object> dbg, String reason) {
        advicesSkipped.incrementAndGet();
        if (dbg != null) dbg.put("skip", reason);
        emitDebug(dbg == null ? new LinkedHashMap<String, Object>() : dbg, "skip." + reason);
        return 0;
    }

    private void incLegSkipped(String reason, String instrumentKey) {
        advicesSkipped.incrementAndGet();
        try {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("reason", reason);
            m.put("ik", instrumentKey);
            m.put("ts", Instant.now());
            stream.publish("metrics.strategy.skipped", "strategy", m);
        } catch (Exception ignored) {
        }
    }

    private void emitMetrics(boolean endOfTick) {
        try {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("advices.created", advicesCreated.get());
            m.put("advices.skipped", advicesSkipped.get());
            m.put("advices.executed", advicesExecuted.get());
            m.put("endOfTick", endOfTick);
            m.put("ts", Instant.now());
            stream.publish("metrics.strategy", "strategy", m);
            try {
                JsonObject d = new JsonObject();
                d.addProperty("advicesCreated", advicesCreated.get());
                d.addProperty("advicesSkipped", advicesSkipped.get());
                d.addProperty("advicesExecuted", advicesExecuted.get());
                d.addProperty("endOfTick", endOfTick);
                audit("strategy.metrics", d);
            } catch (Throwable ignore) {
            }
        } catch (Exception ignored) {
        }
    }

    public enum Bias {CALL, PUT, BOTH}

    public enum PortfolioSide {NONE, HAVE_CALL, HAVE_PUT}

    // -------------------- Inners/DTOs --------------------
    @Data
    public static class LegSpec {
        final LocalDate expiry;
        final BigDecimal strike;
        final OptionType type;

        LegSpec(LocalDate e, BigDecimal k, OptionType t) {
            expiry = e;
            strike = k;
            type = t;
        }
    }

    private static class ExitHints {
        BigDecimal sl, tp;
    }

    public static class Ind {
        public BigDecimal ema20, ema50, rsi, adx, atr, atrPct, bbU, bbL, vwap, close;
    }

    private static class Structure {
        BigDecimal donHi, donLo, vwap, lastClose;
    }

    private record Score(int total, String breakdown) {
    }

    // -------------------- Inners --------------------

    public static class PDRange {
        public BigDecimal pdh, pdl;

        public PDRange(BigDecimal h, BigDecimal l) {
            this.pdh = h;
            this.pdl = l;
        }
    }

    private record Pcr(BigDecimal oiPcr, BigDecimal volPcr) {

        Bias toBias() {
            int ce = 0, pe = 0;

            if (oiPcr != null) {
                // High PCR (≥1.20) → tilt CE (CALL); Low PCR (≤0.80) → tilt PE (PUT)
                if (oiPcr.compareTo(PCR_BEARISH_MIN) >= 0) ce++;
                else if (oiPcr.compareTo(PCR_BULLISH_MAX) <= 0) pe++;
            }
            if (volPcr != null) {
                if (volPcr.compareTo(PCR_BEARISH_MIN) >= 0) ce++;
                else if (volPcr.compareTo(PCR_BULLISH_MAX) <= 0) pe++;
            }

            if (ce > 0 && pe == 0) return Bias.CALL;  // tilt CE
            if (pe > 0 && ce == 0) return Bias.PUT;   // tilt PE
            return Bias.BOTH;                          // neutral/ambiguous
        }

        String toReason() {
            return "PCR[OI=" + (oiPcr == null ? "—" : oiPcr) + ", VOL=" + (volPcr == null ? "—" : volPcr) + "]";
        }

        int points() {
            int p = 10;
            if (toBias() != Bias.BOTH) p += 10;
            return p;
        }
    }

    private static class IvStats {
        BigDecimal avgIv, ceAvg, peAvg, ivPctile;
        double skewAbs;
        boolean highAvgIv, wideSkew;

        String toReason() {
            return "IV[avg=" + avgIv + ", ce=" + ceAvg + ", pe=" + peAvg + ", %ile=" + ivPctile + ", skew=" + String.format(Locale.ROOT, "%.1f", skewAbs) + "]";
        }

        int points() {
            int p = 10;
            if (wideSkew) p += 5;
            if (highAvgIv) p -= 5;
            return p;
        }
    }

    private static class OiDelta {
        boolean ceRising, peRising, priceFalling, priceRising;
        BigDecimal ceDelta, peDelta;

        String toReason() {
            return "OIΔ[CE=" + ceDelta + ", PE=" + peDelta + ", price " + (priceRising ? "↑" : priceFalling ? "↓" : "—") + "]";
        }
    }


    // Kafkaesque audit helper (optional). Emits to TOPIC_AUDIT.
    private void audit(String event, com.google.gson.JsonObject data) {
        try {
            if (bus == null) return;
            java.time.Instant now = java.time.Instant.now();
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("ts", now.toEpochMilli());
            o.addProperty("ts_iso", now.toString());
            o.addProperty("event", event);
            o.addProperty("source", "strategy");
            if (data != null) o.add("data", data);
            bus.publish(com.trade.frankenstein.trader.bus.EventBusConfig.TOPIC_AUDIT, "strategy", o.toString());
        } catch (Throwable ignore) { /* best-effort */ }
    }

}
