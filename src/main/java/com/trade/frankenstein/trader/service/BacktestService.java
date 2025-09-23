package com.trade.frankenstein.trader.service;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final MarketDataService marketDataService;

    // ---- Strategy knobs (MVP) ----
    private static final int EMA_FAST = 12;
    private static final int EMA_SLOW = 26;
    private static final int ATR_N = 14;
    private static final int ADX_N = 14;
    private static final double ADX_MIN = 20.0;    // gate
    private static final double ATR_MULT = 1.5;    // risk unit
    private static final double TARGET_R_MULT = 2; // take-profit at 2R
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * Run a simple EMA(12/26) + ADX(14) trend strategy with ATR(14)-based stop/target
     * on historical candles pulled via MarketDataService.
     *
     * @param strategyId    free text label (echoed in result)
     * @param instrumentKey e.g. "NSE_INDEX|Nifty 50"
     * @param unit          "minutes" | "hour" | "day"
     * @param interval      e.g. "5", "15", "60"
     * @param from          inclusive (bar end time >= from)
     * @param to            inclusive (bar end time <= to)
     */
    public Summary run(String strategyId,
                       String instrumentKey,
                       String unit,
                       String interval,
                       Instant from,
                       Instant to) {
        Summary out = new Summary();
        out.setStrategyId(strategyId);
        out.setInstrumentKey(instrumentKey);
        out.setUnit(unit);
        out.setInterval(interval);
        out.setFrom(from);
        out.setTo(to);

        try {
            Optional<BarSeries> opt = marketDataService.getTa4jSeries(instrumentKey, unit, interval);
            if (!opt.isPresent()) {
                out.setMessage("No series available");
                return out;
            }
            BarSeries series = opt.get();
            if (series.isEmpty()) {
                out.setMessage("Empty series");
                return out;
            }

            // Clip to [from, to]
            int startIdx = firstIndexAtOrAfter(series, from);
            int endIdx = lastIndexAtOrBefore(series, to);
            if (startIdx < 0 || endIdx < 0 || endIdx <= startIdx) {
                out.setMessage("No bars in requested window");
                return out;
            }

            // Indicators
            ClosePriceIndicator close = new ClosePriceIndicator(series);
            EMAIndicator emaFast = new EMAIndicator(close, EMA_FAST);
            EMAIndicator emaSlow = new EMAIndicator(close, EMA_SLOW);
            ADXIndicator adx = new ADXIndicator(series, ADX_N);
            ATRIndicator atr = new ATRIndicator(series, ATR_N);

            // Warmup guard (ensure indicators fully initialized)
            int warmup = Math.max(Math.max(EMA_SLOW, ATR_N), ADX_N) + 1;
            int i0 = Math.max(startIdx, warmup);

            // Simulation state
            boolean inLong = false, inShort = false;
            double entry = 0, stop = 0, target = 0, riskAbs = 0;

            int trades = 0, wins = 0, losses = 0;
            double sumRR = 0.0;

            for (int i = i0; i <= endIdx; i++) {
                double c = val(close, i);
                double ef = val(emaFast, i);
                double es = val(emaSlow, i);
                double a = val(adx, i);
                double atrNow = val(atr, i);

                Bar bar = series.getBar(i);
                double high = bar.getHighPrice().doubleValue();
                double low = bar.getLowPrice().doubleValue();

                if (!inLong && !inShort) {
                    // Entry rules
                    boolean upTrend = ef > es;
                    boolean dnTrend = ef < es;
                    boolean longOk = c > ef && upTrend && a >= ADX_MIN;
                    boolean shortOk = c < ef && dnTrend && a >= ADX_MIN;

                    if (longOk) {
                        inLong = true;
                        entry = c;
                        riskAbs = ATR_MULT * atrNow;
                        stop = entry - riskAbs;
                        target = entry + TARGET_R_MULT * riskAbs;
                        continue;
                    }
                    if (shortOk) {
                        inShort = true;
                        entry = c;
                        riskAbs = ATR_MULT * atrNow;
                        stop = entry + riskAbs;
                        target = entry - TARGET_R_MULT * riskAbs;
                        continue;
                    }
                } else if (inLong) {
                    // Exit rules: stop first (conservative), then target, then trend flip
                    if (low <= stop) {
                        double exit = stop;
                        double rr = (exit - entry) / riskAbs;
                        trades++;
                        losses++;
                        sumRR += rr;
                        inLong = false;
                        continue;
                    }
                    if (high >= target) {
                        double exit = target;
                        double rr = (exit - entry) / riskAbs;
                        trades++;
                        wins++;
                        sumRR += rr;
                        inLong = false;
                        continue;
                    }
                    if (c < ef) { // trend fade
                        double exit = c;
                        double rr = (exit - entry) / riskAbs;
                        trades++;
                        if (rr >= 0) wins++;
                        else losses++;
                        sumRR += rr;
                        inLong = false;
                    }
                } else if (inShort) {
                    // Exit rules for short: stop first, then target, then trend flip
                    if (high >= stop) {
                        double exit = stop;
                        double rr = (entry - exit) / riskAbs;
                        trades++;
                        losses++;
                        sumRR += rr;
                        inShort = false;
                        continue;
                    }
                    if (low <= target) {
                        double exit = target;
                        double rr = (entry - exit) / riskAbs;
                        trades++;
                        wins++;
                        sumRR += rr;
                        inShort = false;
                        continue;
                    }
                    if (c > ef) { // trend fade
                        double exit = c;
                        double rr = (entry - exit) / riskAbs;
                        trades++;
                        if (rr >= 0) wins++;
                        else losses++;
                        sumRR += rr;
                        inShort = false;
                    }
                }
            }

            // Force-close if position left open at the end
            if (inLong || inShort) {
                Bar last = series.getBar(endIdx);
                double c = last.getClosePrice().doubleValue();
                double rr = inLong ? (c - entry) / riskAbs : (entry - c) / riskAbs;
                trades++;
                if (rr >= 0) wins++;
                else losses++;
                sumRR += rr;
                inLong = inShort = false;
            }

            out.setTrades(trades);
            out.setWins(wins);
            out.setLosses(losses);
            out.setWinRatePct(trades == 0 ? 0.0 : (wins * 100.0) / trades);
            out.setAvgRR(trades == 0 ? 0.0 : (sumRR / trades));
            out.setMessage("ok");
            return out;

        } catch (Throwable t) {
            log.warn("BacktestService.run error: {}", t.toString());
            out.setMessage("error: " + t.getMessage());
            return out;
        }
    }

    // ----------- helpers -----------

    private int firstIndexAtOrAfter(BarSeries s, Instant from) {
        if (from == null) return 0;
        for (int i = 0; i < s.getBarCount(); i++) {
            Instant end = s.getBar(i).getEndTime().toInstant();
            if (!end.isBefore(from)) return i;
        }
        return -1;
    }

    private int lastIndexAtOrBefore(BarSeries s, Instant to) {
        if (to == null) return s.getEndIndex();
        for (int i = s.getBarCount() - 1; i >= 0; i--) {
            Instant end = s.getBar(i).getEndTime().toInstant();
            if (!end.isAfter(to)) return i;
        }
        return -1;
    }

    private double val(Indicator<?> ind, int i) {
        return Double.parseDouble(ind.getValue(i).toString());
    }

    // ----------- DTO -----------

    @Data
    public static class Summary {
        private String strategyId;
        private String instrumentKey;
        private String unit;
        private String interval;
        private Instant from;
        private Instant to;

        private int trades;
        private int wins;
        private int losses;
        private double winRatePct;
        private double avgRR;

        private String message; // "ok" or reason
    }
}
