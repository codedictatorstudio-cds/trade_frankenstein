package com.trade.frankenstein.trader.model.upstox;

import java.util.List;

public final class PlaceOrderResponse {

    public final List<String> orderIds;
    public final Integer latencyMs;

    public PlaceOrderResponse(List<String> orderIds, Integer latencyMs) {
        this.orderIds = orderIds == null ? List.of() : List.copyOf(orderIds);
        this.latencyMs = latencyMs;
    }
}
