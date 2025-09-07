package com.trade.frankenstein.trader.service.signals;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.enums.MarketRegime;
import com.trade.frankenstein.trader.model.entity.MarketRegimeSnapshotEntity;
import com.trade.frankenstein.trader.model.entity.market.CandleEntity;
import com.trade.frankenstein.trader.repo.MarketRegimeSnapshotRepository;
import com.trade.frankenstein.trader.repo.market.CandleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketSignalsService {

    private final CandleRepository candleRepository;
    private final MarketRegimeSnapshotRepository marketRegimeSnapshotRepository;

    @Value("${trade.symbol.nifty:NIFTY}")
    private String niftySymbol;

    @Value("${trade.momentum.lookback-candles:120}")
    private int lookbackCandles;

    @Value("${trade.momentum.window:60}")
    private int zscoreWindow;

    @Value("${trade.regime.z.up:1.0}")
    private double zUp;

    @Value("${trade.regime.z.down:-1.0}")
    private double zDown;

    @Value("${trade.regime.rv.high:0.006}")
    private double rvHigh;

    @Value("${trade.regime.rv.low:0.002}")
    private double rvLow;

    @Value("${trade.regime.atrpct.range:0.0025}")
    private double atrPctRange;

    /**
     * Compute z-score from candles (no persistence).
     */
    @Transactional(readOnly = true)
    public Result<BigDecimal> computeMomentumZScore() {
        return computeMomentumZScore(niftySymbol);
    }

    /**
     * Compute z-score from candles (no persistence).
     */
    @Transactional(readOnly = true)
    public Result<BigDecimal> computeMomentumZScore(String symbol) {
        List<CandleEntity> recent = candleRepository
                .findAll(PageRequest.of(0, Math.max(lookbackCandles * 2, 200),
                        Sort.by(Sort.Direction.DESC, "openTime")))
                .stream()
                .filter(c -> c.getSymbol() != null && c.getSymbol().equalsIgnoreCase(symbol))
                .limit(lookbackCandles)
                .collect(Collectors.toList());
        if (recent.size() < Math.max(30, zscoreWindow + 2)) {
            return Result.fail("NOT_ENOUGH_CANDLES");
        }

        Collections.reverse(recent); // oldest → newest

        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < recent.size(); i++) {
            double prev = asDouble(recent.get(i - 1).getClosePrice());
            double cur = asDouble(recent.get(i).getClosePrice());
            if (prev > 0) rets.add((cur - prev) / prev);
        }
        if (rets.size() < zscoreWindow + 1) {
            return Result.fail("NOT_ENOUGH_RETURNS");
        }

        int lastIdx = rets.size() - 1;
        double last = rets.get(lastIdx);
        int from = Math.max(0, lastIdx - zscoreWindow);
        int to = lastIdx;
        double mean = mean(rets, from, to);
        double sd = stddev(rets, from, to, mean);
        double z = (sd > 1e-12) ? (last - mean) / sd : 0.0;

        return Result.ok(BigDecimal.valueOf(z).setScale(6, RoundingMode.HALF_UP));
    }

    /**
     * Classify regime and persist a snapshot (uses computed z-score + simple vol/range proxies).
     */
    @Transactional
    public Result<MarketRegimeSnapshotEntity> refreshRegime() {
        return refreshRegime(niftySymbol);
    }

    @Transactional
    public Result<MarketRegimeSnapshotEntity> refreshRegime(String symbol) {
        // 1) Pull candles once
        List<CandleEntity> recent = candleRepository
                .findAll(PageRequest.of(0, Math.max(lookbackCandles * 2, 200),
                        Sort.by(Sort.Direction.DESC, "openTime")))
                .stream()
                .filter(c -> c.getSymbol() != null && c.getSymbol().equalsIgnoreCase(symbol))
                .limit(lookbackCandles)
                .collect(Collectors.toList());
        if (recent.size() < Math.max(30, zscoreWindow + 2)) {
            return Result.fail("NOT_ENOUGH_CANDLES");
        }
        Collections.reverse(recent);

        // returns
        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < recent.size(); i++) {
            double prev = asDouble(recent.get(i - 1).getClosePrice());
            double cur = asDouble(recent.get(i).getClosePrice());
            if (prev > 0) rets.add((cur - prev) / prev);
        }
        if (rets.size() < zscoreWindow + 1) {
            return Result.fail("NOT_ENOUGH_RETURNS");
        }

        int lastIdx = rets.size() - 1;
        double last = rets.get(lastIdx);
        int from = Math.max(0, lastIdx - zscoreWindow);
        int to = lastIdx;
        double mean = mean(rets, from, to);
        double sd = stddev(rets, from, to, mean);
        double z = (sd > 1e-12) ? (last - mean) / sd : 0.0;

        // realized vol proxy
        double rv = sd;

        // ATR% proxy
        double atrLike = avgRange(recent, 14);
        double lastClose = asDouble(recent.get(recent.size() - 1).getClosePrice());
        double atrPct = (lastClose > 0) ? atrLike / lastClose : 0.0;

        MarketRegime regime = classifyRegime(z, rv, atrPct);
        double strength = Math.min(1.0, Math.abs(z) / 2.0);

        MarketRegimeSnapshotEntity snap = new MarketRegimeSnapshotEntity();
        snap.setAsOf(Instant.now());
        snap.setRegime(regime);
        snap.setStrength(strength);

        MarketRegimeSnapshotEntity saved = marketRegimeSnapshotRepository.save(snap);
        return Result.ok(saved);
    }

    /**
     * Recompute momentum/regime regularly during market hours.
     */
    @Scheduled(fixedDelayString = "${trade.market.refresh-ms:30000}")
    public void refreshSignalsScheduled() {
        if (!isMarketHoursNowIst()) return;
        try {
            refreshRegime("NIFTY"); // keep your implementation
        } catch (Throwable t) {
            log.warn("refreshSignalsScheduled failed", t);
        }
    }

    private static boolean isMarketHoursNowIst() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && !t.isAfter(LocalTime.of(15, 30));
    }

    // ---- helpers ----
    private MarketRegime classifyRegime(double z, double rv, double atrPct) {
        if (z >= zUp && rv >= rvLow) return MarketRegime.BULLISH;
        if (z <= zDown && rv >= rvLow) return MarketRegime.BEARISH;
        if (atrPct < atrPctRange) return MarketRegime.RANGE_BOUND;
        if (rv > rvHigh) return MarketRegime.HIGH_VOLATILITY;
        if (rv < rvLow) return MarketRegime.LOW_VOLATILITY;
        return MarketRegime.UNKNOWN;
    }

    private static double asDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static double mean(List<Double> a, int from, int to) {
        if (to <= from) return 0;
        double s = 0;
        for (int i = from; i < to; i++) s += a.get(i);
        return s / (to - from);
    }

    private static double stddev(List<Double> a, int from, int to, double m) {
        if (to <= from) return 0;
        double s = 0;
        for (int i = from; i < to; i++) {
            double d = a.get(i) - m;
            s += d * d;
        }
        return Math.sqrt(s / (to - from));
    }

    private static double avgRange(List<CandleEntity> c, int n) {
        int N = Math.min(Math.max(n, 1), c.size());
        if (N <= 1) return 0;
        double s = 0;
        for (int i = c.size() - N; i < c.size(); i++) {
            double hi = asDouble(c.get(i).getHighPrice());
            double lo = asDouble(c.get(i).getLowPrice());
            s += Math.max(0, hi - lo);
        }
        return s / N;
    }
}
