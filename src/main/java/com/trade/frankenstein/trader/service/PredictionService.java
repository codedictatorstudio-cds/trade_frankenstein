package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.model.dto.DirectionPrediction;
import com.trade.frankenstein.trader.model.dto.VolatilityPrediction;

import java.util.Optional;

/**
 * Service for predictive market models (e.g., ML-based forecasts).
 */
public interface PredictionService {

    /**
     * Predicts short-term price direction for the given instrument.
     *
     * @param instrumentKey Instrument identifier (e.g., "NIFTY")
     * @param horizonMinutes Prediction horizon in minutes
     * @return Optional prediction (UP/DOWN/FLAT) with confidence
     */
    Optional<DirectionPrediction> predictDirection(String instrumentKey, int horizonMinutes);

    /**
     * Predicts near-term volatility change for the given instrument.
     *
     * @param instrumentKey Instrument identifier
     * @param horizonMinutes Prediction horizon in minutes
     * @return Optional volatility prediction
     */
    Optional<VolatilityPrediction> predictVolatility(String instrumentKey, int horizonMinutes);

    /**
     * Estimates overall prediction confidence based on combined models.
     *
     * @param directionPred Short-term direction prediction
     * @param volatilityPred Volatility prediction
     * @return Combined confidence score (0..1)
     */
    default double calculateOverallPredictionConfidence(DirectionPrediction directionPred,
                                                        VolatilityPrediction volatilityPred) {
        double c1 = directionPred != null ? directionPred.getConfidence() : 0;
        double c2 = volatilityPred != null ? volatilityPred.getConfidence() : 0;
        return (c1 + c2) / (c1>0 && c2>0 ? 2 : 1);
    }
}
