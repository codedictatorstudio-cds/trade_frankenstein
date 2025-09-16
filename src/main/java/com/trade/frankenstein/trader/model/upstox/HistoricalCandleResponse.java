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
public class HistoricalCandleResponse {

    private String status;
    private CandleData data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class CandleData {
        private List<Candle> candles;

    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public class Candle {
        private long timestamp;
        private double open;
        private double high;
        private double low;
        private double close;
        private int volume;
        private double openInterest;
    }

}
