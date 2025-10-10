package com.trade.frankenstein.trader.model.documents;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Document
@Data
public class VolatilityPrediction {

    @Id
    private String id;
    private String symbol;
    private BigDecimal predictedVolatility;
    private BigDecimal currentVolatility;
    private BigDecimal confidence;
    private Duration horizon;
    private String model; // GARCH, LSTM, SVM
    private Map<String, Double> parameters;
    private Instant createdAt;

    public String toReason() {
        return String.format("Vol Prediction: %.2f%% (conf: %.2f, model: %s)",
                predictedVolatility.doubleValue(), confidence.doubleValue(), model);
    }
}
