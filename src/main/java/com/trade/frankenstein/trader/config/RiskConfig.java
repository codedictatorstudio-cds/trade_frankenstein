package com.trade.frankenstein.trader.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class RiskConfig {
    @Value("${risk.ddcap.default:3.0}")
    private float defaultDdCap;

    private final Map<String, Integer> maxLots = new HashMap<>();
    private final Map<String, Integer> maxDelta = new HashMap<>();

    @Value("${risk.max.lots.NIFTY:30}")
    public void setNiftyLots(int v) {
        maxLots.put("NIFTY", v);
    }

    @Value("${risk.max.lots.BANKNIFTY:20}")
    public void setBankNiftyLots(int v) {
        maxLots.put("BANKNIFTY", v);
    }

    @Value("${risk.max.delta.NIFTY:5000}")
    public void setNiftyDelta(int v) {
        maxDelta.put("NIFTY", v);
    }

    @Value("${risk.max.delta.BANKNIFTY:5000}")
    public void setBankNiftyDelta(int v) {
        maxDelta.put("BANKNIFTY", v);
    }

    public float getDdCap() {
        return defaultDdCap;
    }

    public int getMaxLots(String key) {
        return maxLots.getOrDefault(key.toUpperCase(), 30);
    }

    public int getMaxDelta(String key) {
        return maxDelta.getOrDefault(key.toUpperCase(), 10000);
    }
}

