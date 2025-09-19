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
public class OHLC_Quotes {

    private String status;
    private Map<String, OHLCData> data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OHLCData {
        private double last_price;
        private String instrument_token;
        private Ohlc prev_ohlc;
        private Ohlc live_ohlc;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Ohlc {
        private double open;
        private double high;
        private double low;
        private double close;
        private int volume;
        private long ts;
    }
}
