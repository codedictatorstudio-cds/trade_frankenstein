package com.trade.frankenstein.trader.common.constants;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "trade.backtest")
public class BacktestProperties {

    private boolean enabled = true;
    private int maxMonths = 3;
    private int maxCandlesPerSeries = 100_000;
    private double riskFreeRatePct = 5.0;
}
