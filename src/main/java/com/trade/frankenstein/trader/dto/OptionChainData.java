package com.trade.frankenstein.trader.dto;

import com.trade.frankenstein.trader.model.documents.OptionContract;

import java.util.List;
import java.util.Map;

public class OptionChainData {
    private String underlying;
    private String expiry;
    private List<OptionContract> calls;
    private List<OptionContract> puts;
    private Map<String, Object> marketData;

    public OptionChainData() {
    }

    public OptionChainData(String underlying, String expiry, List<OptionContract> calls, List<OptionContract> puts) {
        this.underlying = underlying;
        this.expiry = expiry;
        this.calls = calls;
        this.puts = puts;
    }

    // Getters and Setters
    public String getUnderlying() {
        return underlying;
    }

    public void setUnderlying(String underlying) {
        this.underlying = underlying;
    }

    public String getExpiry() {
        return expiry;
    }

    public void setExpiry(String expiry) {
        this.expiry = expiry;
    }

    public List<OptionContract> getCalls() {
        return calls;
    }

    public void setCalls(List<OptionContract> calls) {
        this.calls = calls;
    }

    public List<OptionContract> getPuts() {
        return puts;
    }

    public void setPuts(List<OptionContract> puts) {
        this.puts = puts;
    }

    public Map<String, Object> getMarketData() {
        return marketData;
    }

    public void setMarketData(Map<String, Object> marketData) {
        this.marketData = marketData;
    }
}
