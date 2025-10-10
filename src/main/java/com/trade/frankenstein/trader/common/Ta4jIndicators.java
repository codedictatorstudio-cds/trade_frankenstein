package com.trade.frankenstein.trader.common;

import com.trade.frankenstein.trader.model.documents.Candle;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.donchian.DonchianChannelLowerIndicator;
import org.ta4j.core.indicators.donchian.DonchianChannelUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

// ta4j


/**
 * Minimal ta4j wrapper for StrategyService.
 * Works with ta4j 0.17 (Java 11/17). For 0.18 (JDK 21), the API is similar;
 * the series builder uses Instant instead of ZonedDateTime.
 */
public class Ta4jIndicators {

    private final int emaFast;
    private final int emaSlow;
    private final int adxPeriod;
    private final int atrPeriod;
    private final int donchianWindow;
    private final int vwapLookback;

    public Ta4jIndicators(int emaFast, int emaSlow, int adxPeriod, int atrPeriod,
                          int donchianWindow, int vwapLookback) {
        this(emaFast, emaSlow, adxPeriod, atrPeriod, donchianWindow, vwapLookback, ZoneId.of("Asia/Kolkata"));
    }

    public Ta4jIndicators(int emaFast, int emaSlow, int adxPeriod, int atrPeriod,
                          int donchianWindow, int vwapLookback, ZoneId zone) {
        this.emaFast = emaFast;
        this.emaSlow = emaSlow;
        this.adxPeriod = adxPeriod;
        this.atrPeriod = atrPeriod;
        this.donchianWindow = donchianWindow;
        this.vwapLookback = vwapLookback;
    }

    /**
     * Build a BarSeries from your Candle list. Uses endTime = openTime + timeframe.
     */
    public BarSeries buildSeries(String name, List<Candle> candles, Duration timeframe) {
        Objects.requireNonNull(timeframe, "timeframe");
        BarSeries series = new BaseBarSeriesBuilder().withName(name == null ? "series" : name).build();
        // inside buildSeries(...)
        for (Candle c : candles) {
            if (c == null || c.getOpenTime() == null) continue;

            Instant end = c.getOpenTime().plus(timeframe);
            series.addBar(new BaseBar(timeframe, ZonedDateTime.from(end), c.getOpenPrice(), c.getHighPrice(), c.getLowPrice(), c.getClosePrice(), c.getVolume()));
        }

        return series;
    }

    public EMAIndicator emaFast(BarSeries s) {
        return new EMAIndicator(new ClosePriceIndicator(s), emaFast);
    }

    public EMAIndicator emaSlow(BarSeries s) {
        return new EMAIndicator(new ClosePriceIndicator(s), emaSlow);
    }

    public ADXIndicator adx(BarSeries s) {
        return new ADXIndicator(s, adxPeriod);
    }

    public ATRIndicator atr(BarSeries s) {
        return new ATRIndicator(s, atrPeriod);
    }

    public DonchianChannelUpperIndicator donchianUpper(BarSeries s) {
        return new DonchianChannelUpperIndicator(s, donchianWindow);
    }

    public DonchianChannelLowerIndicator donchianLower(BarSeries s) {
        return new DonchianChannelLowerIndicator(s, donchianWindow);
    }

    public VWAPIndicator vwap(BarSeries s) {
        return new VWAPIndicator(s, vwapLookback);
    }
}
