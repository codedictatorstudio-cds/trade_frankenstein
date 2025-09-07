package com.trade.frankenstein.trader.model.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class UpstoxTrade {
    public String exchange;
    public String product;

    @JsonProperty("trading_symbol")
    public String tradingSymbol;

    @JsonProperty("instrument_token")
    public String instrumentToken;

    @JsonProperty("order_type")
    public String orderType;

    @JsonProperty("transaction_type")
    public String transactionType;

    public int quantity;

    @JsonProperty("exchange_order_id")
    public String exchangeOrderId;

    @JsonProperty("order_id")
    public String orderId;

    @JsonProperty("exchange_timestamp")
    public String exchangeTimestamp;

    @JsonProperty("average_price")
    public BigDecimal averagePrice;

    @JsonProperty("trade_id")
    public String tradeId;

    @JsonProperty("order_ref_id")
    public String orderRefId;

    @JsonProperty("order_timestamp")
    public String orderTimestamp;
}
