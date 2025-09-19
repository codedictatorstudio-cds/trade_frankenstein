package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TradeChargesResponse {

    private double total;
    private double brokerage;
    private Taxes taxes;
    private Charges charges;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Taxes {
        private double gst;
        private double stt;
        private double stamp_duty;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Charges {
        private Double transaction;
        private Double clearing;
        private Double ipft;
        private Double others;
        private Double sebi_turnover;
        private Double demat_transaction;
    }
}
