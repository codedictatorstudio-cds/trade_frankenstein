package com.trade.frankenstein.trader.model.documents;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document
@Data
public class EnsemblePrediction {

    @Id
    private String id;
    private List<MLPrediction> predictions;
    private BigDecimal weightedPrediction;
    private BigDecimal confidence;
    private String methodology; // VOTING, STACKING, BAGGING
    private Map<String, Double> modelWeights;
    private Instant createdAt;

    public String toReason() {
        return String.format("Ensemble: %.2f (conf: %.2f, models: %d)",
                weightedPrediction.doubleValue(), confidence.doubleValue(),
                predictions.size());
    }
}
