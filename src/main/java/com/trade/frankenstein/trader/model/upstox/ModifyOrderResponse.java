package com.trade.frankenstein.trader.model.upstox;

public final class ModifyOrderResponse {

    public final String orderId;
    public final Integer latencyMs;

    public ModifyOrderResponse(String orderId, Integer latencyMs) {
        this.orderId = orderId;
        this.latencyMs = latencyMs;
    }
}
