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
public class OptionGreekResponse {

    private String status;
    private Map<String, OptionGreek> data;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class OptionGreek {
        private double last_price;
        private String instrument_token;
        private int ltq;
        private int volume;
        private double cp;
        private double iv;
        private double vega;
        private double gamma;
        private double theta;
        private double delta;
        private int oi;
    }
}
