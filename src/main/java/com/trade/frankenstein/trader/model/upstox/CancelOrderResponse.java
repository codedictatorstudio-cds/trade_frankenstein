package com.trade.frankenstein.trader.model.upstox;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public final class CancelOrderResponse {

    public final String orderId;
    public final Integer latencyMs;

    public CancelOrderResponse(String orderId, Integer latencyMs) {
        this.orderId = orderId;
        this.latencyMs = latencyMs;
    }
}
