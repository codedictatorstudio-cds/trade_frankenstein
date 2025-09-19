package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OptionsExitResponse {

    private String status;
    private OrderIdsData data;
    private Object errors;
    private Summary summary;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OrderIdsData {
        private List<String> order_ids;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Summary {
        private int total;
        private int success;
        private int error;
    }
}
