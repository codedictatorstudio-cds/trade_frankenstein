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
public class OrderTradesResponse {

    private String status;
    private List<TradeData> data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TradeData {
        private String exchange;
        private String product;
        private String trading_symbol;
        private String tradingsymbol;
        private String instrument_token;
        private String order_type;
        private String transaction_type;
        private int quantity;
        private String exchange_order_id;
        private String order_id;
        private String exchange_timestamp;
        private double average_price;
        private String trade_id;
        private String order_ref_id;
        private String order_timestamp;
    }
}
