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
public class HoldingsResponse {

    private String status;
    private List<HoldingData> data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class HoldingData {
        private String isin;
        private int cnc_used_quantity;
        private String collateral_type;
        private String company_name;
        private double haircut;
        private String product;
        private int quantity;
        private String trading_symbol;
        private String tradingsymbol;
        private double last_price;
        private double close_price;
        private double pnl;
        private double day_change;
        private double day_change_percentage;
        private String instrument_token;
        private double average_price;
        private int collateral_quantity;
        private int collateral_update_quantity;
        private int t1_quantity;
        private String exchange;
    }
}
