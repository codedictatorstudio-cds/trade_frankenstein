package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FundsResponse {

    private String status;
    private FundsData data;

    @Data
    public static class FundsData {
        private FundDetails equity;
        private FundDetails commodity;
    }

    @Data
    public static class FundDetails {
        private double used_margin;
        private double payin_amount;
        private double span_margin;
        private double adhoc_margin;
        private double notional_cash;
        private double available_margin;
        private double exposure_margin;
    }
}
