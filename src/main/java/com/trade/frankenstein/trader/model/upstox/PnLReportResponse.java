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
public class PnLReportResponse {

    private String status;
    private List<PnLReportItem> data;
    private PnLReportMetadata metadata;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class PnLReportItem {
        private int quantity;
        private String isin;
        private String scrip_name;
        private String trade_type;
        private String buy_date;
        private double buy_average;
        private String sell_date;
        private double sell_average;
        private double buy_amount;
        private double sell_amount;
    }

    @Data
    public class PnLReportMetadata {
        private PnLReportPage page;
    }

    @Data
    public class PnLReportPage {
        private int page_number;
        private int page_size;
    }
}
