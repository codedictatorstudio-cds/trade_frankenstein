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
public class PortfolioResponse {

    private String status;
    private List<PortfolioData> data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortfolioData {
        private String exchange;
        private double multiplier;
        private double value;
        private double pnl;
        private String product;
        private String instrument_token;
        private double average_price;
        private double buy_value;
        private int overnight_quantity;
        private double day_buy_value;
        private double day_buy_price;
        private double overnight_buy_amount;
        private int overnight_buy_quantity;
        private int day_buy_quantity;
        private double day_sell_value;
        private double day_sell_price;
        private double overnight_sell_amount;
        private int overnight_sell_quantity;
        private int day_sell_quantity;
        private int quantity;
        private double last_price;
        private double unrealised;
        private double realised;
        private double sell_value;
        private String trading_symbol;
        private String tradingsymbol;
        private double close_price;
        private double buy_price;
        private double sell_price;
    }
}
