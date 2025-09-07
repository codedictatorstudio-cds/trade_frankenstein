package com.trade.frankenstein.trader.model.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class UpstoxPosition {
    @JsonProperty("instrument_token")
    public String instrumentToken;

    @JsonProperty("trading_symbol")
    public String tradingSymbol;

    public String exchange;
    public String product; // I/D/MTF

    @JsonProperty("buy_qty")
    public Integer buyQty;

    @JsonProperty("sell_qty")
    public Integer sellQty;

    @JsonProperty("net_qty")
    public Integer netQty;

    @JsonProperty("buy_avg_price")
    public BigDecimal buyAvgPrice;

    @JsonProperty("sell_avg_price")
    public BigDecimal sellAvgPrice;

    @JsonProperty("ltp")
    public BigDecimal ltp;

    @JsonProperty("pnl")
    public BigDecimal pnl;
}
