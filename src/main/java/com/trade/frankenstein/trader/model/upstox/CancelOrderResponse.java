package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CancelOrderResponse {

    private String status;
    private PlaceOrderData data;
    private PlaceOrderMetadata metadata;

    @Data
    public static class PlaceOrderData {
        private String order_id;
    }

    @Data
    public static class PlaceOrderMetadata {
        private int latency;
    }
}
