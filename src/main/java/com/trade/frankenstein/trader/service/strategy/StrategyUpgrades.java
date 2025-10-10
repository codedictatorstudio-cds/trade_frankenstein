package com.trade.frankenstein.trader.service.strategy;

import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;


/**
 * StrategyUpgrades — single-class helper (Java 8, no reflection).
 * <p>
 * What it adds:
 * 1) pickTightestSpreadStrike(...) — ranks candidate strikes by bid–ask spread% and returns the best.
 * 2) shouldActivateTrailing(...) + recomputeTrailing(...) — trail only when ADX is rising and price > Day VWAP.
 * 3) runBacktest(...) — minimal bar-by-bar simulator that reuses your strategy via a DecideFn.
 * <p>
 * How to wire:
 * - Use QuoteProvider to bridge to your UpstoxService.getQuote()/LTP APIs.
 * - Use IndicatorProvider to supply ADX and VWAP (from your MarketData/Candles).
 * - In StrategyService, after delta/IV filters, call pickTightestSpreadStrike(...) for the final instrument key.
 * - In EngineService.manageProtectiveOrders(...), gate SL trailing via shouldActivateTrailing(...),
 * then call recomputeTrailing(...) to bump the stop.
 * - For backtests, call runBacktest(...) and pass your DecideFn that returns BUY/SELL/NONE using your live strategy logic.
 */
public class StrategyUpgrades {

    private static final BigDecimal BIG_N = new BigDecimal("9E9");

    /* =========================================================
     *  Step-9 Feature Toggles (FlagsService)
     *  - No reflection; Java 8.
     *  - We try strategy-specific flags by name via FlagName.valueOf(...)
     *    so the code compiles even if you haven't added them yet.
     *    Unknown names gracefully fall back to sensible defaults.
     * ========================================================= */

    private StrategyUpgrades() {
    }

    /**
     * Ranks instrument keys by % spread = (ask - bid) / mid, using LTP/Day OHLC as fallback.
     *
     * @param candidateInstrumentKeys list of instrument keys already filtered by delta/IV/etc.
     * @param quoteProvider           adapter to your existing quote API (no reflection).
     * @param maxSpreadPctCap         optional cap (e.g., 0.015 = 1.5%). If null/<=0, no cap filter is applied.
     * @return best instrument key by tightest spread, or null if none usable.
     */
    public static String pickTightestSpreadStrike(
            List<String> candidateInstrumentKeys,
            QuoteProvider quoteProvider,
            BigDecimal maxSpreadPctCap
    ) {
        if (candidateInstrumentKeys == null || candidateInstrumentKeys.isEmpty()) return null;

        List<SpreadRow> rows = new ArrayList<>();
        for (String key : candidateInstrumentKeys) {
            try {
                QuoteView q = quoteProvider.quoteOf(key);
                if (q == null) {
                    rows.add(new SpreadRow(key, BIG_N));
                    continue;
                }
                BigDecimal bid = nz(q.bid());
                BigDecimal ask = nz(q.ask());
                BigDecimal ltp = nz(q.ltp());
                BigDecimal mid;

                if (gt0(bid) && gt0(ask)) {
                    mid = bid.add(ask).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
                } else if (gt0(ltp)) {
                    mid = ltp;
                    // Approximate a "spread" using OHLC if book is thin
                    BigDecimal o = nz(q.open());
                    BigDecimal c = nz(q.close());
                    BigDecimal proxy = (gt0(o) && gt0(c))
                            ? o.subtract(c).abs()
                            : ltp.multiply(new BigDecimal("0.01")); // 1% proxy
                    bid = ltp.subtract(proxy.divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP));
                    ask = ltp.add(proxy.divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP));
                } else {
                    rows.add(new SpreadRow(key, BIG_N));
                    continue;
                }

                BigDecimal spreadAbs = ask.subtract(bid);
                if (spreadAbs.signum() < 0) spreadAbs = BigDecimal.ZERO;

                BigDecimal spreadPct = gt0(mid)
                        ? spreadAbs.divide(mid, 6, RoundingMode.HALF_UP)
                        : BIG_N;

                rows.add(new SpreadRow(key, spreadPct));
            } catch (Exception t) {
                rows.add(new SpreadRow(key, BIG_N));
            }
        }

        Collections.sort(rows, new Comparator<SpreadRow>() {
            @Override
            public int compare(SpreadRow a, SpreadRow b) {
                return a.spreadPct.compareTo(b.spreadPct);
            }
        });

        if (maxSpreadPctCap != null && maxSpreadPctCap.signum() > 0) {
            for (SpreadRow r : rows) {
                if (r.spreadPct.compareTo(maxSpreadPctCap) <= 0) return r.key;
            }
            // If nothing meets cap, fall back to the tightest anyway
        }

        return rows.isEmpty() ? null : rows.get(0).key;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean gt0(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    /**
     * Gate trailing: only true if ADX is rising and LTP > Day VWAP.
     */
    public static boolean shouldActivateTrailing(
            BigDecimal adxPrev, BigDecimal adxNow,
            BigDecimal ltp, BigDecimal dayVwap
    ) {
        if (!gt0(adxPrev) || !gt0(adxNow) || !gt0(ltp) || !gt0(dayVwap)) return false;
        boolean adxRising = adxNow.compareTo(adxPrev) > 0;
        boolean priceAboveVwap = ltp.compareTo(dayVwap) > 0;
        return adxRising && priceAboveVwap;
    }

    /**
     * Recompute trailing stop.
     * - First trail: move SL to breakeven once profit >= trailTriggerPct.
     * - Subsequent trails: set SL = max(current SL, LTP * (1 - trailStepPct)).
     */
    public static ExitPlan recomputeTrailing(
            ExitPlan plan,
            BigDecimal entryPrice,
            BigDecimal ltp,
            BigDecimal adxPrev,
            BigDecimal adxNow,
            BigDecimal dayVwap,
            BigDecimal trailTriggerPct, // e.g., 0.20 (20% in option premium)
            BigDecimal trailStepPct      // e.g., 0.05 (5% behind LTP)
    ) {
        if (plan == null || !gt0(entryPrice) || !gt0(ltp)) return plan;

        if (!shouldActivateTrailing(adxPrev, adxNow, ltp, dayVwap)) {
            return plan; // trailing gate closed
        }

        BigDecimal gainPct = ltp.subtract(entryPrice)
                .divide(entryPrice, 6, RoundingMode.HALF_UP);

        if (!plan.trailed) {
            if (gainPct.compareTo(nz(trailTriggerPct)) >= 0) {
                // first trail → breakeven
                plan.stopPrice = entryPrice;
                plan.trailed = true;
            }
            return plan;
        }

        // subsequent trails: follow price with step
        BigDecimal step = ltp.multiply(nz(trailStepPct));
        BigDecimal newStop = ltp.subtract(step);
        if (plan.stopPrice == null || newStop.compareTo(plan.stopPrice) > 0) {
            plan.stopPrice = newStop;
        }
        return plan;
    }


    public static Decision none() {
        return new Decision(Side.NONE, null);
    }

    /**
     * Minimal backtest: walks candles, calls your DecideFn, enters when BUY, exits on SL/TP/time or reverse signal.
     * You can plug in SL/TP via the ExitPlan and recomputeTrailing(...) just like live.
     */
    public static BacktestResult runBacktest(
            List<? extends CandleView> candles,
            IndicatorProvider indicators,
            DecideFn decideFn,
            BigDecimal slPct,          // e.g., 0.25
            BigDecimal tpPct,          // e.g., 0.30
            Duration timeStop,         // e.g., Duration.ofMinutes(40)
            BigDecimal trailTriggerPct,// e.g., 0.20
            BigDecimal trailStepPct    // e.g., 0.05
    ) {
        BacktestResult out = new BacktestResult();
        if (candles == null || candles.isEmpty()) return out;

        TradeSim open = null;
        ExitPlan plan = null;
        Map<String, Object> session = new HashMap<>();

        for (int i = 0; i < candles.size(); i++) {
            CandleView c = candles.get(i);
            Instant ts = c.openTime();
            BarContext ctx = new BarContext(ts, c, indicators, session);

            // Exit logic if in trade
            if (open != null) {
                BigDecimal ltp = c.close(); // using close as proxy for fill
                BigDecimal entry = open.entry;

                // time stop
                if (timeStop != null && open.entryTs.plus(timeStop).isBefore(ts)) {
                    closeTrade(out, open, ts, ltp);
                    open = null;
                    plan = null;
                } else {
                    // SL/TP check
                    BigDecimal stop = plan != null ? plan.stopPrice : entry.multiply(BigDecimal.ONE.subtract(slPct));
                    BigDecimal target = plan != null && plan.targetPrice != null
                            ? plan.targetPrice
                            : entry.multiply(BigDecimal.ONE.add(tpPct));

                    if (stop != null && ltp.compareTo(stop) <= 0) {
                        closeTrade(out, open, ts, stop);
                        open = null;
                        plan = null;
                    } else if (ltp.compareTo(target) >= 0) {
                        closeTrade(out, open, ts, target);
                        open = null;
                        plan = null;
                    } else {
                        // trailing recompute (ADX rising & price > VWAP)
                        BigDecimal adxPrev = indicators.adx(prevTs(candles, i));
                        BigDecimal adxNow = indicators.adx(ts);
                        BigDecimal dayVwap = indicators.dayVwap(ts);

                        plan = recomputeTrailing(
                                plan != null ? plan : new ExitPlan(
                                        entry.multiply(BigDecimal.ONE.subtract(slPct)),
                                        entry.multiply(BigDecimal.ONE.add(tpPct))
                                ),
                                entry,
                                ltp,
                                adxPrev,
                                adxNow,
                                dayVwap,
                                trailTriggerPct,
                                trailStepPct
                        );
                    }
                }
            }

            // Entry logic (one position at a time)
            if (open == null) {
                Decision d = decideFn.decide(ctx);
                if (d != null && d.side == Side.BUY) {
                    open = new TradeSim(d.instrumentKey, ts, c.close());
                    plan = new ExitPlan(
                            open.entry.multiply(BigDecimal.ONE.subtract(slPct)),
                            open.entry.multiply(BigDecimal.ONE.add(tpPct))
                    );
                }
            }
        }

        // close at last if still open (mark-to-market)
        if (open != null) {
            CandleView last = candles.get(candles.size() - 1);
            closeTrade(out, open, last.openTime(), last.close());
        }
        return out;
    }

    private static Instant prevTs(List<? extends CandleView> candles, int i) {
        return i > 0 ? candles.get(i - 1).openTime() : candles.get(i).openTime();
    }



    /* =========================================================
     *  #14 Minimal backtest runner (bar-by-bar)
     *  Reuses your strategy via DecideFn (no reflection).
     * ========================================================= */

    private static void closeTrade(BacktestResult out, TradeSim t, Instant ts, BigDecimal px) {
        t.exitTs = ts;
        t.exit = px;
        t.pnl = px.subtract(t.entry);
        out.trades.add(t);
        out.pnlSum = out.pnlSum.add(t.pnl);
        if (t.pnl.signum() >= 0) out.wins++;
        else out.losses++;
    }

    public enum Side {NONE, BUY, SELL}

    public interface QuoteView {
        BigDecimal bid();   // best bid (nullable -> return BigDecimal.ZERO)

        BigDecimal ask();   // best ask (nullable -> return BigDecimal.ZERO)

        BigDecimal ltp();   // last traded price (nullable -> return BigDecimal.ZERO)

        BigDecimal open();  // day open (nullable -> return BigDecimal.ZERO)

        BigDecimal close(); // prev close (nullable -> return BigDecimal.ZERO)
    }


    public interface QuoteProvider {
        /**
         * Return a QuoteView for the given instrumentKey (e.g., "NIFTY 24750 CE").
         */
        QuoteView quoteOf(String instrumentKey);
    }

    public interface CandleView {
        Instant openTime();

        BigDecimal open();

        BigDecimal high();

        BigDecimal low();

        BigDecimal close();

        long volume();
    }

    public interface IndicatorProvider {
        BigDecimal adx(Instant asOf);

        BigDecimal dayVwap(Instant asOf);
    }

    /**
     * Your live strategy wrapped as a pure function for backtests.
     */
    public interface DecideFn {
        Decision decide(BarContext ctx);
    }

    private record SpreadRow(String key, BigDecimal spreadPct) {
    }

    public static final class ExitPlan {
        public BigDecimal stopPrice;   // current SL
        public BigDecimal targetPrice; // TP
        public boolean trailed;    // whether we have begun trailing

        public ExitPlan(BigDecimal stopPrice, BigDecimal targetPrice) {
            this.stopPrice = stopPrice;
            this.targetPrice = targetPrice;
            this.trailed = false;
        }
    }

    public record Decision(Side side, String instrumentKey) {
        public Decision(Side side, String instrumentKey) {
            this.side = side == null ? Side.NONE : side;
            this.instrumentKey = instrumentKey;
        }

        public static Decision none() {
            return new Decision(Side.NONE, null);
        }
    }

    public record BarContext(Instant ts, CandleView candle, IndicatorProvider indicators, Map<String, Object> session) {
    }

    public static final class TradeSim {
        public final String instrumentKey;
        public final Instant entryTs;
        public final BigDecimal entry;
        public Instant exitTs;
        public BigDecimal exit;
        public BigDecimal pnl; // absolute

        public TradeSim(String instrumentKey, Instant entryTs, BigDecimal entry) {
            this.instrumentKey = instrumentKey;
            this.entryTs = entryTs;
            this.entry = entry;
        }
    }

    public static final class BacktestResult {
        public final List<TradeSim> trades = new ArrayList<>();
        public BigDecimal pnlSum = BigDecimal.ZERO;
        public int wins = 0, losses = 0;
    }

    // =========================================================
    // Kafkaesque-friendly helpers (no direct Kafka coupling)
    // =========================================================

    /**
     * Build a uniform kafkaesque envelope for Strategy events.
     * - Adds ts (epoch), ts_iso (ISO-8601), event, source, and optional data.
     * - Java 8 only, no reflection.
     */
    public static JsonObject buildKafkaesqueEvent(String event, String source, JsonObject data) {
        java.time.Instant now = java.time.Instant.now();
        JsonObject o = new JsonObject();
        o.addProperty("ts", now.toEpochMilli());
        o.addProperty("ts_iso", now.toString());
        o.addProperty("event", event == null ? "strategy.unknown" : event);
        o.addProperty("source", source == null || source.trim().isEmpty() ? "strategy" : source);
        if (data != null) o.add("data", data);
        return o;
    }

    /**
     * Shortcut with source defaulted to "strategy".
     */
    public static JsonObject buildStrategyEvent(String event, JsonObject data) {
        return buildKafkaesqueEvent(event, "strategy", data);
    }

    /**
     * String variants (useful for bus.publish(topic, key, json)).
     */
    public static String buildKafkaesqueEventString(String event, String source, JsonObject data) {
        return buildKafkaesqueEvent(event, source, data).toString();
    }

    public static String buildStrategyEventString(String event, JsonObject data) {
        return buildKafkaesqueEvent(event, "strategy", data).toString();
    }

    /**
     * Convenience data builders for common upgrade actions (optional).
     */
    public static JsonObject dataPickSpread(String symbol, String expiry, double bestStrike, double spreadPct, int candidates) {
        JsonObject d = new JsonObject();
        if (symbol != null) d.addProperty("symbol", symbol);
        if (expiry != null) d.addProperty("expiry", expiry);
        d.addProperty("bestStrike", bestStrike);
        d.addProperty("spreadPct", spreadPct);
        d.addProperty("candidates", candidates);
        return d;
    }

    public static JsonObject dataTrailingDecision(boolean activate, BigDecimal adxPrev, BigDecimal adxNow, boolean aboveVwap) {
        JsonObject d = new JsonObject();
        d.addProperty("activate", activate);
        if (adxPrev != null) d.addProperty("adxPrev", adxPrev);
        if (adxNow != null) d.addProperty("adxNow", adxNow);
        d.addProperty("aboveVwap", aboveVwap);
        return d;
    }

}
