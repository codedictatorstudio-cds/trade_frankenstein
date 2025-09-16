package com.trade.frankenstein.trader.model.upstox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LTP_Quotes {

    private String status;
    private Map<String, LTPQuoteData> data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class LTPQuoteData {
        private double last_price;
        private String instrument_token;
        private int ltq;
        private int volume;
        private double cp;
    }
}
