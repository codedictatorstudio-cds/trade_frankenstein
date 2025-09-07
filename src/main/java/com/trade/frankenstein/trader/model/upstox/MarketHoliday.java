package com.trade.frankenstein.trader.model.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class MarketHoliday {

    public String date;      // yyyy-MM-dd
    public String description;
    public String exchange;  // NSE/BSE/NFO...

    @JsonProperty("is_trading")
    public Boolean isTrading;

    @JsonProperty("is_settlement")
    public Boolean isSettlement;
}
