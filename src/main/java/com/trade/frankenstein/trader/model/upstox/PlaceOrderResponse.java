package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PlaceOrderResponse {

    private String status;
    private PlaceOrderData data;
    private PlaceOrderMetadata metadata;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class PlaceOrderData {
        private List<String> order_ids;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class PlaceOrderMetadata {
        private int latency;
    }

}
