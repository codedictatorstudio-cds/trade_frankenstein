package com.trade.frankenstein.trader.test.service;

import com.trade.frankenstein.trader.service.StrategyService;
import com.upstox.api.IntraDayCandleData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategyIndicatorsTest {

    @Test
    void indicatorsComputesAtrPctFromSeededCandles() {
        StrategyService svc = new StrategyService(); // uses only pure methods here

        // Only getCandles() is available → mock the SDK object and stub getCandles()
        IntraDayCandleData data = mock(IntraDayCandleData.class);
        List<List<Object>> candles = new ArrayList<>();
        when(data.getCandles()).thenReturn(candles);

        // Build 150 five-minute bars, oldest -> newest, gentle up-trend
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        double px = 20000.0;
        double lastClose = 0.0;

        for (int i = 0; i < 150; i++) {
            double open  = px;
            double high  = px + 10;
            double low   = px - 10;
            double close = px + 2;
            long ts = now.minusSeconds((149L - i) * 300L).getEpochSecond(); // ascending chronology

            // Upstox row format: [epochSeconds, open, high, low, close, volume]
            candles.add(Arrays.asList(ts, open, high, low, close, 1000.0));

            lastClose = close;
            px += 2;
        }

        // Call public API (no reflection)
        StrategyService.Ind ind = svc.indicators(data, BigDecimal.valueOf(lastClose));

        assertThat(ind).as("Indicators object").isNotNull();
        assertThat(ind.atr).as("ATR should be computed").isNotNull();
        assertThat(ind.atrPct).as("ATR%% should be computed").isNotNull();
        assertThat(ind.atrPct.doubleValue()).isGreaterThan(0.0);
        assertThat(ind.atrPct.doubleValue()).isLessThan(10.0);
    }
}
