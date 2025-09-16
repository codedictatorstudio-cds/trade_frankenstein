package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OrderGetResponse {

    private String status;
    private OrderGetData data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class OrderGetData {
        private String exchange;
        private String product;
        private double price;
        private int quantity;
        private String status;
        private String tag;
        private String instrument_token;
        private String placed_by;
        private String trading_symbol;
        private String tradingsymbol;
        private String order_type;
        private String validity;
        private double trigger_price;
        private int disclosed_quantity;
        private String transaction_type;
        private double average_price;
        private int filled_quantity;
        private int pending_quantity;
        private String status_message;
        private String status_message_raw;
        private String exchange_order_id;
        private String parent_order_id;
        private String order_id;
        private String variety;
        private String order_timestamp;
        private String exchange_timestamp;
        private boolean is_amo;
        private String order_request_id;
        private String order_ref_id;
    }


}
