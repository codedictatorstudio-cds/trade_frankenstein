package com.trade.frankenstein.trader.model.upstox;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class UpstoxOrder {

    public String exchange;
    public String product; // I/D/MTF
    public BigDecimal price;
    public int quantity;
    public String status; // open/pending/complete/rejected/cancelled/...
    public String tag;

    @JsonProperty("instrument_token")
    public String instrumentToken;

    @JsonProperty("trading_symbol")
    public String tradingSymbol;

    @JsonProperty("order_type")
    public String orderType; // MARKET/LIMIT/SL/SL-M

    public String validity;  // DAY/IOC

    @JsonProperty("trigger_price")
    public BigDecimal triggerPrice;

    @JsonProperty("disclosed_quantity")
    public Integer disclosedQuantity;

    @JsonProperty("transaction_type")
    public String transactionType; // BUY/SELL

    @JsonProperty("average_price")
    public BigDecimal averagePrice;

    @JsonProperty("filled_quantity")
    public Integer filledQuantity;

    @JsonProperty("pending_quantity")
    public Integer pendingQuantity;

    @JsonProperty("exchange_order_id")
    public String exchangeOrderId;

    @JsonProperty("parent_order_id")
    public String parentOrderId;

    @JsonProperty("order_id")
    public String orderId;

    public String variety;

    @JsonProperty("order_timestamp")
    public String orderTimestamp;

    @JsonProperty("exchange_timestamp")
    public String exchangeTimestamp;

    @JsonProperty("is_amo")
    public Boolean isAmo;

    @JsonProperty("order_request_id")
    public String orderRequestId;

    @JsonProperty("order_ref_id")
    public String orderRefId;
}
