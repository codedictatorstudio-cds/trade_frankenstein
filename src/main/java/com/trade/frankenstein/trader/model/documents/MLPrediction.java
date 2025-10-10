package com.trade.frankenstein.trader.model.documents;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document
@Data
public class MLPrediction {

    @Id
    private String id;
    private String symbol;
    private String predictedDirection; // BULLISH, BEARISH, NEUTRAL
    private BigDecimal confidence;
    private String modelType;
    private List<String> features;
    private Map<String, Double> featureImportance;
    private BigDecimal priceTarget;
    private Duration predictionHorizon;
    private Instant createdAt;
    private Instant expiresAt;

    public String toReason() {
        return String.format("ML Prediction: %s (conf: %.2f, model: %s)",
                predictedDirection, confidence.doubleValue(), modelType);
    }
}
