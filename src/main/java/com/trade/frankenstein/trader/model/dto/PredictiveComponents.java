package com.trade.frankenstein.trader.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PredictiveComponents {
    private DirectionPrediction shortTermDirection;
    private VolatilityPrediction volatilityForecast;
    private OptionsFlowBias optionsFlowBias;
    private MicrostructureSignals microstructureSignals;
    private double predictionConfidence; // 0..1

    public boolean isEmpty() {
        return shortTermDirection==null
                && volatilityForecast==null
                && optionsFlowBias==null
                && microstructureSignals==null;
    }
}
