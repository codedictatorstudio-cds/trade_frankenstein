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
public class OrderHistoryResponse {

    private String status;
    private List<OrderHistoryData> data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OrderHistoryData {
        private String exchange;
        private double price;
        private String product;
        private int quantity;
        private String status;
        private String tag;
        private String validity;
        private double average_price;
        private int disclosed_quantity;
        private String exchange_order_id;
        private String exchange_timestamp;
        private String instrument_token;
        private boolean is_amo;
        private String status_message;
        private String order_id;
        private String order_request_id;
        private String order_type;
        private String parent_order_id;
        private String trading_symbol;
        private String tradingsymbol;
        private String order_timestamp;
        private int filled_quantity;
        private String transaction_type;
        private double trigger_price;
        private String placed_by;
        private String variety;
    }
}
