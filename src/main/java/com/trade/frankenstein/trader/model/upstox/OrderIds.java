package com.trade.frankenstein.trader.model.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class OrderIds {
    @JsonProperty("order_ids")
    public List<String> orderIds;
}
