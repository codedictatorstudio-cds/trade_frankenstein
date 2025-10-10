package com.trade.frankenstein.trader.service.strategy;

import com.trade.frankenstein.trader.model.documents.VolatilityPrediction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class VolatilityPredictionService {

    private final AtomicReference<Double> recentAccuracy = new AtomicReference<>(0.75);

    public VolatilityPrediction predictVolatility(String symbol, java.time.LocalDate expiry, int horizonMinutes) {
        try {
            VolatilityPrediction prediction = new VolatilityPrediction();
            prediction.setId(UUID.randomUUID().toString());
            prediction.setSymbol(symbol);
            prediction.setModel("LSTM_GARCH_HYBRID");
            prediction.setHorizon(Duration.ofMinutes(horizonMinutes));
            prediction.setCreatedAt(Instant.now());

            // Simulate volatility prediction
            // In reality, this would use LSTM/GARCH models trained on historical data
            double baseVolatility = 18.0; // Base annualized volatility

            // Add time-of-day effects
            int hour = java.time.LocalTime.now().getHour();
            double timeAdjustment = 1.0;
            if (hour >= 9 && hour <= 10) timeAdjustment = 1.3; // Opening volatility spike
            if (hour >= 14 && hour <= 15) timeAdjustment = 1.2; // Closing volatility
            if (hour >= 12 && hour <= 13) timeAdjustment = 0.8; // Lunch time calm

            // Add day-of-week effects
            int dayOfWeek = java.time.LocalDate.now().getDayOfWeek().getValue();
            double dayAdjustment = 1.0;
            if (dayOfWeek == 1) dayAdjustment = 1.15; // Monday effect
            if (dayOfWeek == 5) dayAdjustment = 1.1;  // Friday effect
            if (dayOfWeek == 4) dayAdjustment = 1.2;  // Thursday (expiry effect)

            // Add expiry proximity effect
            long daysToExpiry = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), expiry);
            double expiryAdjustment = 1.0;
            if (daysToExpiry <= 1) expiryAdjustment = 1.4; // Expiry day volatility
            else if (daysToExpiry <= 3) expiryAdjustment = 1.2; // Near expiry

            // Random component
            Random random = new Random();
            double randomComponent = 1.0 + (random.nextGaussian() * 0.1); // 10% random variation

            double predictedVol = baseVolatility * timeAdjustment * dayAdjustment * expiryAdjustment * randomComponent;
            predictedVol = Math.max(8.0, Math.min(50.0, predictedVol)); // Reasonable bounds

            prediction.setPredictedVolatility(bd(String.valueOf(predictedVol)));
            prediction.setCurrentVolatility(bd(String.valueOf(baseVolatility)));

            // Confidence based on prediction stability
            double confidence = 0.7 + (0.2 * Math.exp(-Math.abs(predictedVol - baseVolatility) / 10.0));
            prediction.setConfidence(bd(String.valueOf(Math.min(0.95, confidence))));

            // Store parameters used
            Map<String, Double> parameters = new HashMap<>();
            parameters.put("time_adjustment", timeAdjustment);
            parameters.put("day_adjustment", dayAdjustment);
            parameters.put("expiry_adjustment", expiryAdjustment);
            parameters.put("base_volatility", baseVolatility);
            prediction.setParameters(parameters);

            return prediction;
        } catch (Exception e) {
            log.error("Failed to predict volatility: {}", e.getMessage());
            return null;
        }
    }

    public Double getRecentAccuracy() {
        return recentAccuracy.get();
    }

    public void updateAccuracy(double newAccuracy) {
        // Exponential moving average of accuracy
        double current = recentAccuracy.get();
        double updated = current * 0.9 + newAccuracy * 0.1;
        recentAccuracy.set(updated);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
