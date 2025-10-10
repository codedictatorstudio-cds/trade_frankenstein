package com.trade.frankenstein.trader.service.strategy;

import com.trade.frankenstein.trader.enums.SignalType;
import com.trade.frankenstein.trader.model.documents.AlternativeDataSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class AlternativeDataService {

    public AlternativeDataSignal getAggregatedSignal() {
        try {
            AlternativeDataSignal signal = new AlternativeDataSignal();
            signal.setId(UUID.randomUUID().toString());
            signal.setType(SignalType.SOCIAL_MEDIA_SENTIMENT);
            signal.setSource("AGGREGATED");
            signal.setTimestamp(Instant.now());
            signal.setValidityPeriod(Duration.ofMinutes(30));

            // Simulate aggregated signal from multiple sources
            List<Double> signals = Arrays.asList(
                    getSocialMediaSentiment().doubleValue(),
                    getNewsSentiment(),
                    getEconomicIndicatorSignal(),
                    getMacroSentiment()
            );

            double avgSignal = signals.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);

            signal.setStrength(bd(String.valueOf(avgSignal)));
            signal.setReliability(bd("0.75"));

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sources", Arrays.asList("social_media", "news", "economic", "macro"));
            metadata.put("signal_count", signals.size());
            metadata.put("variance", calculateVariance(signals));
            signal.setMetadata(metadata);

            return signal;
        } catch (Exception e) {
            log.error("Failed to get aggregated alternative data signal: {}", e.getMessage());
            return null;
        }
    }

    public BigDecimal getSocialMediaSentiment() {
        try {
            // Simulate social media sentiment analysis
            // In reality, this would connect to Twitter API, Reddit API, etc.
            Random random = new Random();
            double sentiment = (random.nextGaussian() * 0.3); // Mean 0, std 0.3
            sentiment = Math.max(-1.0, Math.min(1.0, sentiment)); // Clamp to [-1, 1]

            return bd(String.valueOf(sentiment));
        } catch (Exception e) {
            log.error("Failed to get social media sentiment: {}", e.getMessage());
            return bd("0.0");
        }
    }

    private double getNewsSentiment() {
        // Simulate news sentiment from financial news sources
        Random random = new Random();
        return random.nextGaussian() * 0.25; // Slightly less volatile than social media
    }

    private double getEconomicIndicatorSignal() {
        // Simulate economic indicator sentiment
        // This could include GDP, employment, inflation data sentiment
        Calendar cal = Calendar.getInstance();
        int dayOfMonth = cal.get(Calendar.DAY_OF_MONTH);

        // Simulate monthly economic data release impact
        if (dayOfMonth <= 5) {
            return new Random().nextGaussian() * 0.4; // Higher volatility during release period
        } else {
            return new Random().nextGaussian() * 0.1; // Lower impact otherwise
        }
    }

    private double getMacroSentiment() {
        // Simulate macro sentiment from bond yields, currency movements, etc.
        return new Random().nextGaussian() * 0.2;
    }

    private double calculateVariance(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0.0);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
