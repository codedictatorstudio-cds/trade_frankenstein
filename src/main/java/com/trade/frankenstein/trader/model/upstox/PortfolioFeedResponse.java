package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PortfolioFeedResponse {

    private PortfolioFeedOrderPosition position;

    private PortfolioFeedOrderHolding holding;

    private PortfolioFeedOrder order;

    private PortfolioFeedGTTOrder gtt_order;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class PortfolioFeedOrderPosition {

        private String update_type;
        private String instrument_token;
        private String instrument_key;
        private double average_price;
        private double buy_value;
        private int overnight_quantity;
        private String exchange;
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
        private int multiplier;
        private int quantity;
        private String product;
        private double sell_value;
        private double buy_price;
        private double sell_price;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortfolioFeedOrderHolding {

        private String update_type;
        private String instrument_token;
        private String instrument_key;
        private double average_price;
        private String isin;
        private int cnc_used_quantity;
        private int collateral_quantity;
        private String collateral_type;
        private int collateral_update_quantity;
        private String company_name;
        private double haircut;
        private String product;
        private int quantity;
        private int t1_quantity;
        private String exchange;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortfolioFeedOrder {

        private String update_type;
        private String user_id;
        private String userId;
        private String exchange;
        private String instrument_token;
        private String instrument_key;
        private String trading_symbol;
        private String tradingsymbol;
        private String product;
        private String order_type;
        private double average_price;
        private double price;
        private double trigger_price;
        private int quantity;
        private int disclosed_quantity;
        private int pending_quantity;
        private String transaction_type;
        private String order_ref_id;
        private String exchange_order_id;
        private String parent_order_id;
        private String validity;
        private String status;
        private boolean is_amo;
        private String variety;
        private String tag;
        private String exchange_timestamp;
        private String status_message;
        private String order_id;
        private String order_request_id;
        private String order_timestamp;
        private int filled_quantity;
        private String guid;
        private String placed_by;
        private String status_message_raw;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PortfolioFeedGTTOrder {

        private String update_type;
        private String type;
        private String exchange;
        private String instrument_token;
        private int quantity;
        private String product;
        private String gtt_order_id;
        private long expires_at;
        private long created_at;
        private List<GTTOrderRule> rules;

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public class GTTOrderRule {
            private String strategy;
            private String status;
            private String trigger_type;
            private double trigger_price;
            private String transaction_type;
            private String message;
            private String order_id;
            private Integer trailing_gap;
        }
    }
}
