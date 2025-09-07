package com.trade.frankenstein.trader.model.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class UpstoxHolding {

    @JsonProperty("instrument_token")
    public String instrumentToken;

    @JsonProperty("trading_symbol")
    public String tradingSymbol;

    public String exchange;
    public Integer quantity;

    @JsonProperty("average_price")
    public BigDecimal averagePrice;

    @JsonProperty("last_price")
    public BigDecimal lastPrice;

    @JsonProperty("pnl")
    public BigDecimal pnl;
}
