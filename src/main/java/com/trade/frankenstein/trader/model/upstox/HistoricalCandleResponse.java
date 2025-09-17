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
    public static class CandleData {
        private List<List<Object>> candles;

    }
}
