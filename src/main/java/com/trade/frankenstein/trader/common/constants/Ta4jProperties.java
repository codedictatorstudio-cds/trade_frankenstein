package com.trade.frankenstein.trader.common.constants;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "trade.ta4j")
public class Ta4jProperties {
    private boolean enabled = true;
    private List<String> timeframes; // e.g., [5m,15m,1h]
    private int emaFast = 12;
    private int emaSlow = 26;
    private int adxPeriod = 14;
    private int atrPeriod = 14;
    private int donchianWindow = 20;
    private int vwapLookback = 30;
}
