package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.enums.OptionType;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.model.documents.DecisionQuality;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.model.upstox.IntradayCandleResponse;
import com.trade.frankenstein.trader.model.upstox.LTP_Quotes;
import com.trade.frankenstein.trader.model.upstox.OptionGreekResponse;
import com.trade.frankenstein.trader.model.upstox.OptionsInstruments;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * StrategyService: fully wired ruleset (items #1..#15).
 * - Indicators (EMA/RSI/ADX/ATR/BB/VWAP), PCR, IV/skew, OI-Δ, Δ target, MTF alignment,
 * breakout/VWAP filters, news/sentiment gates, time-of-day rules, vol-scaled sizing,
 * slippage guard, portfolio-aware throttles, score aggregator, regime/DD brakes,
 * anomaly checks, observability, exit hints (SL/TP/TTL) and Engine hand-off.
 */
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
    private static final BigDecimal IV_PCTILE_SPIKE = bd("85");       // IV%ile ≥85 → high
    private static final double SKEW_WIDE_ABS = 8.0;                   // |IV_PE - IV_CE| ≥ 8pp → wide

    // Δ target window for trend buys
    private static final double DELTA_MIN = 0.35, DELTA_MAX = 0.45;

    // PCR extremes
    private static final BigDecimal PCR_BULLISH_MAX = bd("0.70");
    private static final BigDecimal PCR_BEARISH_MIN = bd("1.30");

    // Time-of-day blocks (IST)
    private static final int BLOCK_FIRST_MIN = 10;
    private static final int BLOCK_LAST_MIN = 25;

    // Exit hints (% of option price) + time-stop
    private static final BigDecimal STOP_PCT = bd("0.25");   // -25%
    private static final BigDecimal TARGET_PCT = bd("0.30"); // +30%
    private static final int TIME_STOP_MIN = 40;

    // Slippage/spread guard from live 1m OHLC
    private static final BigDecimal MAX_SPREAD_PCT = bd("0.04"); // 4%

    // Score aggregator thresholds
    private static final int SCORE_T_LOW = 45;
    private static final int SCORE_T_HIGH = 65;

    // Dedupe + cooldowns
    private static final long DEDUPE_MINUTES = 10;
    private static final long REGIME_FLIP_COOLDOWN_MIN = 15;

    // Strike stepping
    private static final int STRIKE_STEP = 50;

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

    // -------------------- Public entrypoint --------------------

    // -------------------- Public entrypoint --------------------
    public int generateAdvicesNow() {
        int created = 0;
        final long t0 = System.currentTimeMillis();
        Map<String, Object> dbg = new LinkedHashMap<>();
        log.info("Strategy execution started");
        try {
            // 0) Market session/time windows
            if (isBlockedTimeWindow()) {
                log.info("Strategy execution skipped - blocked time window");
                return 0;
            }

            // 1) Decision trend + reasons
            log.info("Fetching decision quality");
            Result<DecisionQuality> dqR = decisionService.getQuality();
            if (dqR == null || !dqR.isOk() || dqR.get() == null) {
                log.info("Strategy execution skipped - no valid decision quality available");
                return 0;
            }
            DecisionQuality dq = dqR.get();
            String trend = up(dq.getTrend() == null ? "NEUTRAL" : dq.getTrend().name());
            List<String> baseReasons = safeList(dq.getReasons());
            log.info("Decision trend: {}, reasons: {}", trend, baseReasons);

            // 2) Index LTP + nearest expiry & stale guard
            log.info("Fetching index LTP and expiry dates");
            BigDecimal spot = getIndexLtp();
            if (spot == null) {
                log.info("Strategy execution skipped - no spot price available");
                return 0;
            }
            LocalDate expiry = nearestExpiry();
            if (expiry == null) {
                log.info("Strategy execution skipped - no valid expiry date available");
                return 0;
            }
            log.info("Spot price: {}, nearest expiry: {}", spot, expiry);

            // 3) Intraday candles (5m) with staleness check
            log.info("Fetching 5-minute candles");
            List<IntradayCandleResponse.Candle> c5 = candles(NIFTY, "minutes", "5");
            if (isStale(c5, 6)) {
                log.info("Strategy execution skipped - stale candle data");
                return 0;
            }

            // 4) Indicators (5m)
            log.info("Computing technical indicators");
            Ind ind5 = indicators(c5, spot);

            // 5) Multi-timeframe alignment
            log.info("Performing multi-timeframe analysis");
            List<IntradayCandleResponse.Candle> c15 = candles(NIFTY, "minutes", "15");
            Ind ind15 = indicators(c15, spot);
            MarketRegime hrReg = marketDataService.getRegimeOn("minutes", "60").orElse(MarketRegime.NEUTRAL);

            boolean adxWeak = ind5.adx != null && ind5.adx.compareTo(ADX_MIN_TREND) < 0;
            String effectiveTrend = adxWeak ? "NEUTRAL" : trend;

            boolean mtfAgree = emaBias(ind5).equals(emaBias(ind15)) && hrReg == mapToRegime(effectiveTrend);
            dbg.put("mtfAgree", mtfAgree);
            log.info("MTF alignment - 5m trend: {}, 15m trend: {}, hourly regime: {}, agree: {}",
                    emaBias(ind5), emaBias(ind15), hrReg, mtfAgree);

            // 6) Structure filters: Donchian(20) + Day VWAP + PDH/PDL context
            log.info("Checking price structure and context");
            Structure struct = structure(c5);
            PDRange pd = marketDataService.getPreviousDayRange(NIFTY).orElse(null);
            boolean breakoutOk = isBreakoutWithTrend(struct, effectiveTrend, spot, pd);
            boolean vwapPullbackOk = isVwapPullbackWithTrend(ind5, effectiveTrend, spot);
            log.info("Structure filters - breakout: {}, vwap pullback: {}", breakoutOk, vwapPullbackOk);

            // >>> GATE by structure (fix: previously computed but unused)
            if (!breakoutOk && !vwapPullbackOk) {
                log.info("Strategy execution stopped - structure conditions not met");
                dbg.put("gate.structure.breakoutOk", breakoutOk);
                dbg.put("gate.structure.vwapPullbackOk", vwapPullbackOk);
                emitDebug(dbg, "gated.structure");
                return 0;
            }

            // 7) News & sentiment gates
            log.info("Checking news and sentiment gates");
            boolean newsBlock = newsBurstBlock(10); // last 10 min burst?
            BigDecimal senti = marketSentiment();
            boolean sentiBlock = (senti != null && senti.compareTo(bd("-10")) < 0) && !adxWeak;
            log.info("News/sentiment - news burst: {}, sentiment: {}, block: {}", newsBlock, senti, (newsBlock || sentiBlock));

            if (newsBlock || sentiBlock) {
                log.info("Strategy execution stopped - news or sentiment gates triggered");
                dbg.put("gate.news", newsBlock);
                dbg.put("gate.sentiment", senti);
                emitDebug(dbg, "gated.news/sentiment");
                return 0;
            }

            // 8) Option-chain intelligence (PCR / OIΔ / IV, IV%ile, skew)
            log.info("Analyzing option chain metrics");
            Pcr pcr = pcr(expiry);
            IvStats ivStats = ivStatsNearAtm(expiry, spot);
            OiDelta oiDelta = oiDeltaTrend(expiry, spot);

            if (pcr != null) {
                log.info("PCR metrics - OI PCR: {}, Volume PCR: {}", pcr.oiPcr, pcr.volPcr);
            }

            if (ivStats != null) {
                log.info("IV metrics - avg: {}, CE avg: {}, PE avg: {}, percentile: {}, skew: {}",
                        ivStats.avgIv, ivStats.ceAvg, ivStats.peAvg, ivStats.ivPctile, ivStats.skewAbs);
            }

            if (oiDelta != null) {
                log.info("OI delta - CE: {}, PE: {}, price rising: {}, price falling: {}",
                        oiDelta.ceDelta, oiDelta.peDelta, oiDelta.priceRising, oiDelta.priceFalling);
            }

            boolean supplyPressureCE = oiDelta != null && oiDelta.ceRising && oiDelta.priceFalling;
            boolean supplyPressurePE = oiDelta != null && oiDelta.peRising && oiDelta.priceRising;
            log.info("Supply pressure - CE: {}, PE: {}", supplyPressureCE, supplyPressurePE);

            // Set OTM distance by volatility (ATR%) and IV/skew
            int stepsOut = (ind5.atrPct != null && ind5.atrPct.compareTo(ATR_PCT_TWO_STEPS) >= 0) ? 2 : 1;
            if (ivStats != null && (ivStats.highAvgIv || ivStats.wideSkew)) stepsOut = Math.max(1, stepsOut - 1);
            log.info("OTM distance - steps out: {}", stepsOut);

            // 9) CE/PE bias (EMA/RSI) + PCR extremes collapse to single-leg
            log.info("Determining option bias from indicators");
            Bias emaRsiBias = biasFromEmaRsi(ind5);
            Bias pcrBias = (pcr == null) ? Bias.BOTH : pcr.toBias();
            Bias merged = mergeBias(mapTrendBias(effectiveTrend), emaRsiBias, pcrBias);
            log.info("Bias analysis - trend bias: {}, EMA/RSI bias: {}, PCR bias: {}, merged: {}",
                    mapTrendBias(effectiveTrend), emaRsiBias, pcrBias, merged);

            // >>> Supply-pressure enforcement (fix: don't flip to BOTH; drop pressured side)
            if (supplyPressureCE) {
                if (merged == Bias.CALL) {
                    log.info("Strategy execution stopped - call bias rejected due to CE supply pressure");
                    emitDebug(dbg, "gate.supply.CE");
                    return 0;
                }
                if (merged == Bias.BOTH) {
                    merged = Bias.PUT;
                    log.info("Adjusted bias from BOTH to PUT due to CE supply pressure");
                }
            }
            if (supplyPressurePE) {
                if (merged == Bias.PUT) {
                    log.info("Strategy execution stopped - put bias rejected due to PE supply pressure");
                    emitDebug(dbg, "gate.supply.PE");
                    return 0;
                }
                if (merged == Bias.BOTH) {
                    merged = Bias.CALL;
                    log.info("Adjusted bias from BOTH to CALL due to PE supply pressure");
                }
            }

            // 10) Time-of-day & expiry-day rules
            log.info("Checking time-of-day and expiry day rules");
            DayOfWeek dow = LocalDate.now(ZoneId.of("Asia/Kolkata")).getDayOfWeek();
            boolean expiryEve = dow == DayOfWeek.THURSDAY || dow == DayOfWeek.FRIDAY;
            if (expiryEve) {
                stepsOut = Math.min(stepsOut, 1);
                log.info("Expiry eve detected ({}), limiting OTM distance to 1 step", dow);
            }

            // 11) Volatility-scaled sizing (can be 0)
            log.info("Calculating position size based on volatility");
            int lots = dynamicLots(ind5.atrPct);
            log.info("Dynamic lot calculation - ATR%: {}, lots: {}", ind5.atrPct, lots);

            // 12) Portfolio overlap throttle (skip same-side if already have it)
            log.info("Checking portfolio overlap");
            PortfolioSide posSide = portfolioSide();
            log.info("Current portfolio side: {}", posSide);
            if (posSide == PortfolioSide.HAVE_CALL && merged == Bias.CALL) {
                log.info("Strategy execution stopped - already have CALL position");
                dbg.put("gate.overlap", "CALL");
                emitDebug(dbg, "skip.same-side-overlap");
                return 0;
            }
            if (posSide == PortfolioSide.HAVE_PUT && merged == Bias.PUT) {
                log.info("Strategy execution stopped - already have PUT position");
                dbg.put("gate.overlap", "PUT");
                emitDebug(dbg, "skip.same-side-overlap");
                return 0;
            }

            // 13) Score aggregator → fire / skip
            log.info("Aggregating strategy score");
            Score score = scoreAll(trend, ind5, pcr, ivStats, mtfAgree, senti);
            log.info("Strategy score: {} - {}", score.total, score.breakdown);
            dbg.put("score", score.total);
            if (score.total < SCORE_T_LOW) {
                log.info("Strategy execution stopped - score too low: {} < {}", score.total, SCORE_T_LOW);
                emitDebug(dbg, "score.low");
                return 0;
            }
            if (!mtfAgree && score.total < SCORE_T_HIGH) {
                log.info("Strategy execution stopped - insufficient score with MTF disagreement: {} < {}", score.total, SCORE_T_HIGH);
                emitDebug(dbg, "score.mid.mtf_disagree");
                return 0;
            }

            // 14) Regime flip cooldown & drawdown brakes
            log.info("Checking regime flip cooldown and drawdown brakes");
            if (recentRegimeFlip()) {
                log.info("Strategy execution stopped - recent regime flip detected");
                emitDebug(dbg, "cooldown.regimeflip");
                return 0;
            }
            if (riskService.isDailyCircuitTripped().orElse(false)) {
                log.info("Strategy execution stopped - daily circuit breaker triggered");
                emitDebug(dbg, "gate.circuit");
                return 0;
            }

            // NEW: service-grade skew near ATM
            Result<BigDecimal> skewRes = optionChainService.getIvSkew(NIFTY, expiry, spot);
            boolean wideSkewSvc = (skewRes != null && skewRes.isOk() && skewRes.get() != null
                    && skewRes.get().abs().compareTo(bd(SKEW_WIDE_ABS)) >= 0);

            // Keep your existing ATR-based stepsOut first, then nudge shallower on wide skew
            if (wideSkewSvc) {
                stepsOut = Math.max(1, stepsOut - 1);
                log.info("Skew wide by service ({}) → shallower OTM (stepsOut={})", skewRes.get(), stepsOut);
            }


            // 15) Build candidate legs (with Δ targeting & slippage guard)
            log.info("Building candidate option legs - bias: {}, strike stepping: {}", merged, stepsOut);
            BigDecimal atm = optionChainService.computeAtmStrike(spot, STRIKE_STEP);
            List<LegSpec> legs = chooseLegs(merged, expiry, atm, stepsOut);
            log.info("Selected ATM strike: {}, generated {} candidate legs", atm, legs.size());

            // NEW: per-side OI-Δ leaders (limited list)
            Result<LinkedHashMap<Integer, Long>> topCe = optionChainService.topOiChange(NIFTY, expiry, OptionType.CALL, 5);
            Result<LinkedHashMap<Integer, Long>> topPe = optionChainService.topOiChange(NIFTY, expiry, OptionType.PUT, 5);

            // NEW: nudge chosen strikes toward nearest OI-Δ leaders (within 100 pts)
            legs = nudgeByTopOi(legs, topCe, topPe);

            // filter by Δ window and slippage/spread
            log.info("Filtering legs by delta range and slippage constraints");
            List<LegSpec> filtered = new ArrayList<>();
            for (LegSpec L : legs) {
                OptionsInstruments.OptionInstrument inst = findContract(L);
                if (inst == null) {
                    log.info("Skipping leg - contract not found: {} {} {}", L.expiry, L.strike, L.type);
                    continue;
                }
                // NEW: IV percentile cap (skip very rich options)
                Result<BigDecimal> ivPct = optionChainService.getIvPercentile(
                        NIFTY, expiry, bd(inst.getStrike_price()), typeOf(inst));

                if (ivPct != null && ivPct.isOk() && ivPct.get() != null
                        && ivPct.get().compareTo(IV_PCTILE_SPIKE) >= 0) {
                    log.info("Skipping leg - IV%%ile too high: {} ≥ {}", ivPct.get(), IV_PCTILE_SPIKE);
                    dbg.put("skip.ivPctile", ivPct.get());
                    continue;
                }

                if (!deltaInRange(inst.getInstrument_key())) {
                    log.info("Skipping leg - delta out of range: {}", inst.getInstrument_key());
                    dbg.put("skip.delta", inst.getInstrument_key());
                    continue;
                }
                if (!ordersService.preflightSlippageGuard(inst.getInstrument_key(), MAX_SPREAD_PCT)) {
                    log.info("Skipping leg - excessive slippage: {}", inst.getInstrument_key());
                    dbg.put("skip.slippage", inst.getInstrument_key());
                    continue;
                }
                filtered.add(new LegSpec(L.expiry, bd(inst.getStrike_price()), typeOf(inst)));
                log.info("Accepted leg: {} {} {}", L.expiry, inst.getStrike_price(), typeOf(inst));
            }

            if (filtered.isEmpty() || lots < 1) {
                log.info("Strategy execution stopped - no valid legs or zero lot size");
                emitDebug(dbg, "no-legs-or-zero-lots");
                return 0;
            }

            // 16) Persist advices (dedupe, exit plan, observability)
            // NEW: rank by tightest spread before persisting
            List<LegSpec> ranked = rankByTightestSpread(filtered);
            int createdNow = 0;
            for (LegSpec L : ranked) {
                OptionsInstruments.OptionInstrument inst = findContract(L);
                if (inst == null) continue;

                String human = humanSymbol(inst);
                if (deduped(human)) continue;

                int lotSize = Math.max(1, inst.getLot_size());
                int qty = lotSize * lots;

                ExitHints x = exitHints(inst.getInstrument_key());

                List<String> rs = new ArrayList<>(baseReasons);
                rs.add(taSummary(ind5));
                if (pcr != null) rs.add(pcr.toReason());
                if (ivStats != null) rs.add(ivStats.toReason());
                if (oiDelta != null) rs.add(oiDelta.toReason());
                rs.add("Score=" + score.total + " (" + score.breakdown + ")");
                rs.add("MTF=" + (mtfAgree ? "AGREE" : "DISAGREE") + ", Reg=" + hrReg);
                rs.add("EXIT: SL=" + (x == null ? "—" : x.sl) + ", TP=" + (x == null ? "—" : x.tp) + ", TTL=" + TIME_STOP_MIN + "m");

                Advice a = new Advice();
                a.setSymbol(human);
                a.setInstrument_token(inst.getInstrument_key());
                a.setTransaction_type("BUY");
                a.setOrder_type("MARKET");
                a.setProduct("MIS");
                a.setValidity("DAY");
                a.setQuantity(qty);
                a.setReason(String.join("; ", rs));
                a.setStatus(AdviceStatus.PENDING);
                a.setCreatedAt(Instant.now());
                a.setUpdatedAt(Instant.now());

                Result<Advice> saved = adviceService.create(a);
                if (saved != null && saved.isOk() && saved.get() != null) {
                    createdNow++;
                    dbg.put("advice", human);
                    dbg.put("qty", qty);
                    emitDebug(dbg, "advice.created");
                }
            }
            created += createdNow;
            return created;
        } catch (Throwable t) {
            log.warn("strategy: failure {}", t.getMessage(), t);
            return created;
        } finally {
            long ms = System.currentTimeMillis() - t0;
            if (ms > 1000) log.info("strategy: run took {} ms", ms);
        }
    }

    // Run periodically (kept short)
    @Scheduled(fixedDelayString = "${trade.strategy.refresh-ms:20000}")
    public void tick() {
        generateAdvicesNow();
    }


    private boolean recentRegimeFlip() {
        try {
            Optional<MarketRegime> r1 = marketDataService.getRegimeOn("minutes", "60");
            Thread.sleep(5); // noop spacing
            Optional<MarketRegime> r2 = marketDataService.getRegimeOn("minutes", "60");
            if (r1.isEmpty() || r2.isEmpty()) return false;
            if (r1.get() != r2.get()) {
                Instant lastFlip = marketDataService.getLastRegimeFlipInstant().orElse(Instant.EPOCH);
                return Duration.between(lastFlip, Instant.now()).toMinutes() < REGIME_FLIP_COOLDOWN_MIN;
            }
        } catch (Throwable ignored) {
            log.info("regime flip check failed");
        }
        return false;
    }

    // -------------------- Bias, legs, delta & exits --------------------

    private enum Bias {CALL, PUT, BOTH}

    private Bias mapTrendBias(String t) {
        return switch (t) {
            case "BULLISH", "UPTREND" -> Bias.CALL;
            case "BEARISH", "DOWNTREND" -> Bias.PUT;
            default -> Bias.BOTH;
        };
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
        for (Bias x : new Bias[]{a, b, c}) {
            if (x == Bias.CALL) ce++;
            else if (x == Bias.PUT) pe++;
        }
        if (ce >= 2) return Bias.CALL;
        if (pe >= 2) return Bias.PUT;
        return Bias.BOTH;
    }

    private List<LegSpec> chooseLegs(Bias b, LocalDate expiry, BigDecimal atm, int stepsOut) {
        BigDecimal up = atm.add(bd(STRIKE_STEP * stepsOut));
        BigDecimal dn = atm.subtract(bd(STRIKE_STEP * stepsOut));
        List<LegSpec> ls = new ArrayList<>();
        if (b == Bias.CALL) ls.add(new LegSpec(expiry, up, OptionType.CALL));
        else if (b == Bias.PUT) ls.add(new LegSpec(expiry, dn, OptionType.PUT));
        else { // BOTH
            ls.add(new LegSpec(expiry, up, OptionType.CALL));
            ls.add(new LegSpec(expiry, dn, OptionType.PUT));
        }
        return ls;
    }

    private boolean deltaInRange(String instrumentKey) {
        try {
            OptionGreekResponse.OptionGreek g = optionChainService.getGreek(instrumentKey).orElse(null);
            if (g == null || g.getDelta() <= 0) return false;
            double d = Math.abs(g.getDelta());
            return d >= DELTA_MIN && d <= DELTA_MAX;
        } catch (Throwable t) {
            return false;
        }
    }


    private ExitHints exitHints(String instrumentKey) {
        try {
            LTP_Quotes q = upstoxService.getMarketLTPQuote(instrumentKey);
            double ltp = q.getData().get(instrumentKey).getLast_price();
            if (ltp <= 0) return null;
            BigDecimal px = bd(ltp);
            ExitHints x = new ExitHints();
            x.sl = px.multiply(BigDecimal.ONE.subtract(STOP_PCT)).setScale(2, RoundingMode.HALF_UP);
            x.tp = px.multiply(BigDecimal.ONE.add(TARGET_PCT)).setScale(2, RoundingMode.HALF_UP);
            return x;
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------- Option-chain intelligence --------------------

    private Pcr pcr(LocalDate expiry) {
        try {
            Result<BigDecimal> r1 = optionChainService.getOiPcr(NIFTY, expiry);
            Result<BigDecimal> r2 = optionChainService.getVolumePcr(NIFTY, expiry);
            return new Pcr(val(r1), val(r2));
        } catch (Throwable t) {
            return null;
        }
    }

    private IvStats ivStatsNearAtm(LocalDate expiry, BigDecimal spot) {
        try {
            BigDecimal atm = optionChainService.computeAtmStrike(spot, STRIKE_STEP);
            Result<List<OptionsInstruments.OptionInstrument>> rng =
                    optionChainService.listContractsByStrikeRange(NIFTY, expiry, atm.subtract(bd(100)), atm.add(bd(100)));

            // FIX: getGreeksForExpiry returns Result<...>, not Optional
            Result<Map<String, OptionGreekResponse.OptionGreek>> gRes =
                    optionChainService.getGreeksForExpiry(NIFTY, expiry);
            Map<String, OptionGreekResponse.OptionGreek> greeks =
                    (gRes != null && gRes.isOk() && gRes.get() != null) ? gRes.get() : new HashMap<>();

            List<Double> ceIv = new ArrayList<>(), peIv = new ArrayList<>();
            List<Double> allIv = new ArrayList<>();
            if (rng != null && rng.isOk() && rng.get() != null) {
                for (OptionsInstruments.OptionInstrument oi : rng.get()) {
                    OptionGreekResponse.OptionGreek g = greeks.get(oi.getInstrument_key());
                    Double iv = (g == null ? null : g.getIv());
                    if (iv == null) continue;
                    allIv.add(iv);
                    if ("CE".equalsIgnoreCase(oi.getUnderlying_type())) ceIv.add(iv);
                    else if ("PE".equalsIgnoreCase(oi.getUnderlying_type())) peIv.add(iv);
                }
            }
            if (allIv.isEmpty() || ceIv.isEmpty() || peIv.isEmpty()) return null;

            double avgIv = avg(allIv);
            double ceAvg = avg(ceIv);
            double peAvg = avg(peIv);
            double skewAbs = Math.abs(peAvg - ceAvg);
            double pctile = percentileRank(allIv, avgIv); // crude proxy for IV%ile

            IvStats s = new IvStats();
            s.avgIv = bd(avgIv).setScale(2, RoundingMode.HALF_UP);
            s.ceAvg = bd(ceAvg).setScale(2, RoundingMode.HALF_UP);
            s.peAvg = bd(peAvg).setScale(2, RoundingMode.HALF_UP);
            s.skewAbs = skewAbs;
            s.ivPctile = bd(pctile * 100).setScale(0, RoundingMode.HALF_UP);
            s.highAvgIv = s.ivPctile.compareTo(IV_PCTILE_SPIKE) >= 0;
            s.wideSkew = skewAbs >= SKEW_WIDE_ABS;
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private OiDelta oiDeltaTrend(LocalDate expiry, BigDecimal spot) {
        try {
            OptionChainService.OiSnapshot prev = optionChainService.getLatestOiSnapshot(NIFTY, expiry, -1).orElse(null);
            OptionChainService.OiSnapshot curr = optionChainService.getLatestOiSnapshot(NIFTY, expiry, 0).orElse(null);
            if (prev == null || curr == null) return null;

            BigDecimal ceDelta = curr.totalCeOi.subtract(prev.totalCeOi);
            BigDecimal peDelta = curr.totalPeOi.subtract(prev.totalPeOi);

            List<IntradayCandleResponse.Candle> c5 = candles(NIFTY, "minutes", "5");
            if (c5 == null || c5.size() < 2) return null;
            double c1 = c5.get(c5.size() - 2).getClose();
            double c2 = c5.get(c5.size() - 1).getClose();

            OiDelta d = new OiDelta();
            d.ceRising = ceDelta != null && ceDelta.compareTo(BigDecimal.ZERO) > 0;
            d.peRising = peDelta != null && peDelta.compareTo(BigDecimal.ZERO) > 0;
            d.priceFalling = c2 < c1;
            d.priceRising = c2 > c1;
            d.ceDelta = ceDelta;
            d.peDelta = peDelta;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------- Structure filters --------------------

    // -------------------- Structure filters --------------------
    private Structure structure(List<IntradayCandleResponse.Candle> cs) {
        Structure s = new Structure();
        if (cs == null || cs.size() < 25) return s;
        int start = cs.size() - BB_N;
        double hi = -1e9, lo = 1e9;
        for (int i = start; i < cs.size(); i++) {
            hi = Math.max(hi, cs.get(i).getHigh());
            lo = Math.min(lo, cs.get(i).getLow());
        }
        s.donHi = bd(hi);
        s.donLo = bd(lo);
        // Session VWAP
        double pv = 0, vv = 0;
        for (IntradayCandleResponse.Candle k : cs) {
            pv += k.getClose() * k.getVolume();
            vv += k.getVolume();
        }
        if (vv > 0) s.vwap = bd(pv / vv);
        s.lastClose = bd(cs.get(cs.size() - 1).getClose());
        return s;
    }

    private boolean isBreakoutWithTrend(Structure st, String trend, BigDecimal spot, PDRange pd) {
        if (st == null || st.donHi == null || st.donLo == null || st.lastClose == null) return false;
        if ("BULLISH".equals(trend) || "UPTREND".equals(trend)) {
            boolean b = st.lastClose.compareTo(st.donHi) >= 0;
            if (pd != null) b &= st.lastClose.compareTo(pd.pdh) >= 0; // confirm above PDH
            return b;
        }
        if ("BEARISH".equals(trend) || "DOWNTREND".equals(trend)) {
            boolean b = st.lastClose.compareTo(st.donLo) <= 0;
            if (pd != null) b &= st.lastClose.compareTo(pd.pdl) <= 0; // confirm below PDL
            return b;
        }
        return false;
    }

    private boolean isVwapPullbackWithTrend(Ind ind, String trend, BigDecimal spot) {
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

    // -------------------- Indicators & data --------------------
    private Ind indicators(List<IntradayCandleResponse.Candle> cs, BigDecimal spot) {
        if (cs == null || cs.size() < 60) return new Ind();
        double[] c = arrClose(cs), h = arrHigh(cs), l = arrLow(cs), v = arrVol(cs);
        Double ema20 = ema(c, EMA_FAST), ema50 = ema(c, EMA_SLOW);
        Double rsi = rsi(c, RSI_N);
        Double atr = atr(h, l, c, ATR_N);
        Double adx = adx(h, l, c, ADX_N);
        Boll bb = boll(c, BB_N, BB_K);
        Double vwap = vwap(c, v);

        Ind i = new Ind();
        i.ema20 = bd(ema20);
        i.ema50 = bd(ema50);
        i.rsi = bd(rsi);
        i.atr = bd(atr);
        i.adx = bd(adx);
        i.bbU = bd(bb.upper);
        i.bbL = bd(bb.lower);
        i.vwap = bd(vwap);
        i.close = bd(c[c.length - 1]);
        if (i.atr != null && spot != null && spot.compareTo(BigDecimal.ZERO) > 0) {
            i.atrPct = i.atr.multiply(bd(100)).divide(spot, 2, RoundingMode.HALF_UP);
        }
        return i;
    }

    private List<IntradayCandleResponse.Candle> candles(String key, String unit, String interval) {
        try {
            IntradayCandleResponse ic = upstoxService.getIntradayCandleData(key, unit, interval);
            return ic == null ? null : ic.toCandleList();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isStale(List<IntradayCandleResponse.Candle> cs, int maxLagMin) {
        try {
            if (cs == null || cs.isEmpty()) return true;
            long lastEpochSec = cs.get(cs.size() - 1).getTimestamp().getEpochSecond();
            Instant last = Instant.ofEpochSecond(lastEpochSec);
            return Duration.between(last, Instant.now()).toMinutes() > maxLagMin;
        } catch (Throwable t) {
            return false;
        }
    }

    private BigDecimal getIndexLtp() {
        try {
            Result<BigDecimal> r = marketDataService.getLtp(NIFTY);
            return (r != null && r.isOk()) ? r.get() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private LocalDate nearestExpiry() {
        try {
            Result<List<LocalDate>> r = optionChainService.listNearestExpiries(NIFTY, 1);
            return (r != null && r.isOk() && r.get() != null && !r.get().isEmpty()) ? r.get().get(0) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------- News & sentiment --------------------

    // -------------------- News & sentiment --------------------
    private boolean newsBurstBlock(int minutes) {
        try {
            Integer burst = newsService.getRecentBurstCount(minutes).orElse(0);
            return burst != null && burst >= 3; // ≥3 items in window → block
        } catch (Throwable t) {
            return false;
        }
    }

    private BigDecimal marketSentiment() {
        try {
            return sentimentService.getMarketSentimentScore().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------- Risk, portfolio, dedupe --------------------

    // -------------------- Risk, portfolio, dedupe --------------------
    private int dynamicLots(BigDecimal atrPct) {
        try {
            Result<RiskSnapshot> r = riskService.getSummary();
            if (r == null || !r.isOk() || r.get() == null) return 0;
            int cap = Optional.ofNullable(r.get().getLotsCap()).orElse(1);
            int used = Optional.ofNullable(r.get().getLotsUsed()).orElse(0);
            int free = Math.max(0, cap - used);
            if (free == 0) return 0;

            // scale lots {0,1} based on ATR% (higher → skip)
            BigDecimal ref = bd("1.00");
            if (atrPct == null) return 1;
            BigDecimal ratio = atrPct.divide(ref, 2, RoundingMode.HALF_UP); // e.g., 1.5x
            return ratio.compareTo(bd("1.5")) > 0 ? 0 : 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    private PortfolioSide portfolioSide() {
        try {
            return tradesService.getOpenPortfolioSide().orElse(PortfolioSide.NONE);
        } catch (Throwable t) {
            return PortfolioSide.NONE;
        }
    }

    private boolean deduped(String humanSymbol) {
        try {
            Result<List<Advice>> list = adviceService.list();
            if (list == null || !list.isOk() || list.get() == null) return false;
            Instant threshold = Instant.now().minus(DEDUPE_MINUTES, ChronoUnit.MINUTES);
            for (Advice a : list.get()) {
                if (a == null || a.getStatus() != AdviceStatus.PENDING) continue;
                if (humanSymbol.equalsIgnoreCase(a.getSymbol()) &&
                        a.getCreatedAt() != null && a.getCreatedAt().isAfter(threshold)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // -------------------- Small helpers --------------------

    // -------------------- Small helpers --------------------
    private OptionsInstruments.OptionInstrument findContract(LegSpec L) {
        try {
            Result<OptionsInstruments.OptionInstrument> r =
                    optionChainService.findContract(NIFTY, L.expiry, L.strike, L.type);
            return (r != null && r.isOk()) ? r.get() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private String humanSymbol(OptionsInstruments.OptionInstrument oi) {
        String side = typeOf(oi).name();
        return "NIFTY " + oi.getStrike_price() + " " + side;
    }

    private OptionType typeOf(OptionsInstruments.OptionInstrument oi) {
        String t = oi.getUnderlying_type() == null ? "" : oi.getUnderlying_type().toUpperCase(Locale.ROOT);
        return "CE".equals(t) ? OptionType.CALL : OptionType.PUT;
    }

    private String taSummary(Ind i) {
        String ema = (i.ema20 != null && i.ema50 != null) ? (i.ema20.compareTo(i.ema50) >= 0 ? "EMA20>EMA50" : "EMA20<EMA50") : "EMA—";
        return "TA:" + ema
                + ", RSI=" + nz(i.rsi) + ", ADX=" + nz(i.adx) + ", ATR%=" + nz(i.atrPct)
                + ", VWAP=" + nz(i.vwap);
    }

    private MarketRegime mapToRegime(String trend) {
        return switch (trend) {
            case "BULLISH", "UPTREND" -> MarketRegime.BULLISH;
            case "BEARISH", "DOWNTREND" -> MarketRegime.BEARISH;
            default -> MarketRegime.NEUTRAL;
        };
    }

    private boolean isBlockedTimeWindow() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        LocalTime t = now.toLocalTime();
        LocalTime open = LocalTime.of(9, 15);
        LocalTime close = LocalTime.of(15, 30);
        if (t.isBefore(open.plusMinutes(BLOCK_FIRST_MIN))) return true;
        return t.isAfter(close.minusMinutes(BLOCK_LAST_MIN));
    }

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

    private static BigDecimal val(Result<BigDecimal> r) {
        return (r != null && r.isOk()) ? r.get() : null;
    }

    private void emitDebug(Map<String, Object> base, String msg) {
        Map<String, Object> p = new LinkedHashMap<>(base);
        p.put("msg", msg);
        p.put("ts", Instant.now());
        try {
            stream.send("decision.debug", p);
        } catch (Throwable ignored) {
        }
    }

    // -------------------- Math --------------------
    private static double[] arrClose(List<IntradayCandleResponse.Candle> cs) {
        return cs.stream().mapToDouble(IntradayCandleResponse.Candle::getClose).toArray();
    }

    private static double[] arrHigh(List<IntradayCandleResponse.Candle> cs) {
        return cs.stream().mapToDouble(IntradayCandleResponse.Candle::getHigh).toArray();
    }

    private static double[] arrLow(List<IntradayCandleResponse.Candle> cs) {
        return cs.stream().mapToDouble(IntradayCandleResponse.Candle::getLow).toArray();
    }

    private static double[] arrVol(List<IntradayCandleResponse.Candle> cs) {
        return cs.stream().mapToDouble(IntradayCandleResponse.Candle::getVolume).toArray();
    }

    private static Double ema(double[] a, int n) {
        if (a.length < n) return null;
        double k = 2.0 / (n + 1.0), sma = 0;
        for (int i = 0; i < n; i++) sma += a[i];
        sma /= n;
        double e = sma;
        for (int i = n; i < a.length; i++) e = a[i] * k + e * (1 - k);
        return e;
    }

    private static Double rsi(double[] c, int n) {
        if (c.length < n + 1) return null;
        double g = 0, l = 0;
        for (int i = 1; i <= n; i++) {
            double ch = c[i] - c[i - 1];
            if (ch > 0) g += ch;
            else l -= ch;
        }
        double ag = g / n, al = l / n;
        for (int i = n + 1; i < c.length; i++) {
            double ch = c[i] - c[i - 1];
            double G = Math.max(ch, 0), L = Math.max(-ch, 0);
            ag = (ag * (n - 1) + G) / n;
            al = (al * (n - 1) + L) / n;
        }
        if (al == 0) return 100.0;
        if (ag == 0) return 0.0;
        double rs = ag / al;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private static Double atr(double[] h, double[] l, double[] c, int n) {
        if (h.length < n + 1 || l.length < n + 1 || c.length < n + 1) return null;
        int m = h.length;
        double[] tr = new double[m];
        tr[0] = h[0] - l[0];
        for (int i = 1; i < m; i++) {
            double hl = h[i] - l[i], hc = Math.abs(h[i] - c[i - 1]), lc = Math.abs(l[i] - c[i - 1]);
            tr[i] = Math.max(hl, Math.max(hc, lc));
        }
        double A = 0;
        for (int i = 1; i <= n; i++) A += tr[i];
        A /= n;
        for (int i = n + 1; i < m; i++) A = ((A * (n - 1)) + tr[i]) / n;
        return A;
    }

    private static Double adx(double[] h, double[] l, double[] c, int n) {
        if (h.length < n + 1 || l.length < n + 1 || c.length < n + 1) return null;
        int m = h.length;
        double[] tr = new double[m], dmp = new double[m], dmn = new double[m];
        for (int i = 1; i < m; i++) {
            double up = h[i] - h[i - 1], dn = l[i - 1] - l[i];
            dmp[i] = (up > dn && up > 0) ? up : 0;
            dmn[i] = (dn > up && dn > 0) ? dn : 0;
            double hl = h[i] - l[i], hc = Math.abs(h[i] - c[i - 1]), lc = Math.abs(l[i] - c[i - 1]);
            tr[i] = Math.max(hl, Math.max(hc, lc));
        }
        double TR = 0, P = 0, N = 0;
        for (int i = 1; i <= n; i++) {
            TR += tr[i];
            P += dmp[i];
            N += dmn[i];
        }
        double[] dx = new double[m];
        int cnt = 0;
        for (int i = n + 1; i < m; i++) {
            TR = TR - (TR / n) + tr[i];
            P = P - (P / n) + dmp[i];
            N = N - (N / n) + dmn[i];
            double diP = TR == 0 ? 0 : 100 * (P / TR), diN = TR == 0 ? 0 : 100 * (N / TR);
            double den = diP + diN;
            dx[i] = den == 0 ? 0 : 100 * Math.abs(diP - diN) / den;
            cnt++;
        }
        if (cnt == 0) return null;
        int s = n + 1, e = m - 1;
        int cts = e - s + 1;
        if (cts < n) {
            double sum = 0;
            for (int i = s; i <= e; i++) sum += dx[i];
            return sum / Math.max(1, cts);
        }
        double adx = 0;
        for (int i = s; i < s + n; i++) adx += dx[i];
        adx /= n;
        for (int i = s + n; i <= e; i++) adx = ((adx * (n - 1)) + dx[i]) / n;
        return adx;
    }

    private static class Boll {
        final double upper, lower;

        Boll(double u, double l) {
            this.upper = u;
            this.lower = l;
        }
    }

    private static Boll boll(double[] c, int n, double k) {
        if (c.length < n) return new Boll(Double.NaN, Double.NaN);
        int s = c.length - n;
        double mean = 0;
        for (int i = s; i < c.length; i++) mean += c[i];
        mean /= n;
        double s2 = 0;
        for (int i = s; i < c.length; i++) {
            double d = c[i] - mean;
            s2 += d * d;
        }
        double sd = Math.sqrt(s2 / n);
        return new Boll(mean + k * sd, mean - k * sd);
    }

    private static Double vwap(double[] c, double[] v) {
        if (c.length == 0 || v.length != c.length) return null;
        double pv = 0, vv = 0;
        for (int i = 0; i < c.length; i++) {
            double vi = Math.max(0, v[i]);
            pv += c[i] * vi;
            vv += vi;
        }
        return vv <= 0 ? null : pv / vv;
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
        List<Double> sorted = data.stream().sorted().toList();
        int count = 0;
        for (double v : sorted) if (v <= x) count++;
        return count / (double) sorted.size();
    }

    // -------------------- Inners --------------------
    private static class Ind {
        BigDecimal ema20, ema50, rsi, adx, atr, atrPct, bbU, bbL, vwap, close;
    }

    private static class Structure {
        BigDecimal donHi, donLo, vwap, lastClose;
    }

    public static class PDRange {
        public BigDecimal pdh, pdl;

        public PDRange(BigDecimal h, BigDecimal l) {
            this.pdh = h;
            this.pdl = l;
        }
    }

    private static class Pcr {
        final BigDecimal oiPcr, volPcr;

        Pcr(BigDecimal o, BigDecimal v) {
            oiPcr = o;
            volPcr = v;
        }

        Bias toBias() {
            int bull = 0, bear = 0;
            if (oiPcr != null) {
                if (oiPcr.compareTo(PCR_BULLISH_MAX) < 0) bull++;
                else if (oiPcr.compareTo(PCR_BEARISH_MIN) > 0) bear++;
            }
            if (volPcr != null) {
                if (volPcr.compareTo(PCR_BULLISH_MAX) < 0) bull++;
                else if (volPcr.compareTo(PCR_BEARISH_MIN) > 0) bear++;
            }
            if (bull > 0 && bear == 0) return Bias.CALL;
            if (bear > 0 && bull == 0) return Bias.PUT;
            return Bias.BOTH;
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

    private static class Score {
        final int total;
        final String breakdown;

        Score(int t, String b) {
            total = t;
            breakdown = b;
        }
    }

    private static class LegSpec {
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

    public enum PortfolioSide {NONE, HAVE_CALL, HAVE_PUT}

    private String emaBias(Ind ind) {
        if (ind == null || ind.ema20 == null || ind.ema50 == null) return "NA";
        int cmp = ind.ema20.compareTo(ind.ema50);
        if (cmp > 0) return "UP";
        if (cmp < 0) return "DOWN";
        return "FLAT";
    }

    // -------------------- Scoring & gating --------------------
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

    private List<LegSpec> nudgeByTopOi(List<LegSpec> legs,
                                       Result<LinkedHashMap<Integer, Long>> topCe,
                                       Result<LinkedHashMap<Integer, Long>> topPe) {
        if (legs == null || legs.isEmpty()) return legs;
        Set<Integer> ceHot = (topCe != null && topCe.isOk()) ? topCe.get().keySet() : Collections.emptySet();
        Set<Integer> peHot = (topPe != null && topPe.isOk()) ? topPe.get().keySet() : Collections.emptySet();

        List<LegSpec> out = new ArrayList<>(legs.size());
        for (LegSpec L : legs) {
            Set<Integer> pool = (L.type == OptionType.CALL) ? ceHot : peHot;
            if (pool.isEmpty()) {
                out.add(L);
                continue;
            }

            int k = L.strike.intValue();
            int best = k;
            int bestDiff = Integer.MAX_VALUE;
            for (int s : pool) {
                int diff = Math.abs(s - k);
                if (diff < bestDiff && diff <= 100) {
                    best = s;
                    bestDiff = diff;
                }
            }
            if (best != k) {
                log.info("Nudged strike {}→{} based on {} OI-Δ leaders", k, best, L.type);
                out.add(new LegSpec(L.expiry, bd(best), L.type));
            } else out.add(L);
        }
        return out;
    }

    // --- Rank by tightest 1m OHLC "spread%" = (high - low) / close (no reflection) ---
    private List<LegSpec> rankByTightestSpread(List<LegSpec> candidates) {
        class Row {
            final LegSpec leg;
            final BigDecimal spread;

            Row(LegSpec l, BigDecimal s) {
                this.leg = l;
                this.spread = s;
            }
        }
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();

        List<Row> rows = new ArrayList<>();
        for (LegSpec L : candidates) {
            OptionsInstruments.OptionInstrument inst = findContract(L);
            if (inst == null) continue;
            String ik = inst.getInstrument_key();

            BigDecimal spreadPct = new BigDecimal("9E9"); // default = very wide (push to end)
            try {
                // Avoid extra imports by fully qualifying the model type
                com.trade.frankenstein.trader.model.upstox.OHLC_Quotes q =
                        upstoxService.getMarketOHLCQuote(ik, "1minute");
                com.trade.frankenstein.trader.model.upstox.OHLC_Quotes.OHLCData d =
                        (q != null && q.getData() != null) ? q.getData().get(ik) : null;

                if (d != null && d.getLive_ohlc() != null) {
                    double hi = d.getLive_ohlc().getHigh();
                    double lo = d.getLive_ohlc().getLow();
                    double cl = d.getLive_ohlc().getClose();
                    if (cl > 0) {
                        spreadPct = BigDecimal.valueOf(hi - lo)
                                .divide(BigDecimal.valueOf(cl), 6, RoundingMode.HALF_UP);
                    }
                }
            } catch (Throwable ignore) {
                // keep spreadPct as big number to de-prioritize this leg
            }
            rows.add(new Row(L, spreadPct));
        }


        // sort ascending by spread%
        rows.sort(Comparator.comparing(a -> a.spread));

        // first try: keep only those within your configured cap (MAX_SPREAD_PCT)
        List<LegSpec> withinCap = new ArrayList<>();
        for (Row r : rows) if (r.spread.compareTo(MAX_SPREAD_PCT) <= 0) withinCap.add(r.leg);
        if (!withinCap.isEmpty()) return withinCap;

        // fallback: return all ranked (tightest first) if none meet the cap
        List<LegSpec> out = new ArrayList<>();
        for (Row r : rows) out.add(r.leg);
        return out;
    }
}

