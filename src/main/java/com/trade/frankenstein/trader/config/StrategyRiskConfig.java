package com.trade.frankenstein.trader.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "trading.risk.per-strategy.ddcap")
public class StrategyRiskConfig {

    // Keys are strategy names, values are DD cap percentages
    private Map<String, Float> strategyDdCap = new HashMap<>();

    public Map<String, Float> getStrategyDdCap() {
        return strategyDdCap;
    }

    public void setStrategyDdCap(Map<String, Float> strategyDdCap) {
        this.strategyDdCap = strategyDdCap;
    }

    public float getDdCapForStrategy(String strat) {
        return strategyDdCap.getOrDefault(strat, 3.0f); // default 3%
    }
}

