package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ModifyOrderResponse {

    private String status;
    private PlaceOrderData data;
    private PlaceOrderMetadata metadata;

    @Data
    public class PlaceOrderData {
        private String order_id;
    }

    @Data
    public class PlaceOrderMetadata {
        private int latency;
    }
}
