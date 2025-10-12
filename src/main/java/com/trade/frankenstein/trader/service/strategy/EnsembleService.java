package com.trade.frankenstein.trader.service.strategy;

import com.trade.frankenstein.trader.model.documents.EnsemblePrediction;
import com.trade.frankenstein.trader.model.documents.MLPrediction;
import com.upstox.api.IntraDayCandleData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class EnsembleService {

    private final AtomicLong predictionCount = new AtomicLong(0);

    public EnsemblePrediction getMultiTimeframePrediction(IntraDayCandleData c5,
                                                          IntraDayCandleData c15,
                                                          IntraDayCandleData c60) {
        try {
            EnsemblePrediction ensemble = new EnsemblePrediction();
            ensemble.setId(UUID.randomUUID().toString());
            ensemble.setMethodology("WEIGHTED_VOTING");
            ensemble.setCreatedAt(Instant.now());

            List<MLPrediction> predictions = new ArrayList<>();
            Map<String, Double> weights = new HashMap<>();

            // Model 1: Short-term (5min)
            MLPrediction shortTerm = createTimeframePrediction(c5, "SHORT_TERM", 0.3);
            if (shortTerm != null) {
                predictions.add(shortTerm);
                weights.put("SHORT_TERM", 0.3);
            }

            // Model 2: Medium-term (15min)
            MLPrediction mediumTerm = createTimeframePrediction(c15, "MEDIUM_TERM", 0.4);
            if (mediumTerm != null) {
                predictions.add(mediumTerm);
                weights.put("MEDIUM_TERM", 0.4);
            }

            // Model 3: Long-term (60min)
            MLPrediction longTerm = createTimeframePrediction(c60, "LONG_TERM", 0.3);
            if (longTerm != null) {
                predictions.add(longTerm);
                weights.put("LONG_TERM", 0.3);
            }

            ensemble.setPredictions(predictions);
            ensemble.setModelWeights(weights);

            // Calculate weighted prediction
            if (!predictions.isEmpty()) {
                double weightedScore = calculateWeightedPrediction(predictions, weights);
                BigDecimal confidence = calculateEnsembleConfidence(predictions);

                ensemble.setWeightedPrediction(bd(String.valueOf(weightedScore)));
                ensemble.setConfidence(confidence);

                predictionCount.incrementAndGet();
            }

            return ensemble;

        } catch (Exception e) {
            log.error("Failed to generate ensemble prediction: {}", e.getMessage());
            return null;
        }
    }

    public Long getRecentPredictionCount() {
        return predictionCount.get();
    }

    // FIXED: Removed unused 'weight' parameter and extracted confidence calculation
    private MLPrediction createTimeframePrediction(IntraDayCandleData candles, String modelType) {
        if (candles == null || candles.getCandles() == null || candles.getCandles().isEmpty()) {
            return null;
        }

        MLPrediction prediction = new MLPrediction();
        prediction.setId(UUID.randomUUID().toString());
        prediction.setModelType(modelType);
        prediction.setCreatedAt(Instant.now());

        // Simulate prediction based on candle patterns
        List<List<Object>> candleData = candles.getCandles();
        int size = candleData.size();

        if (size >= 5) {
            // Simple trend detection
            double first = ((Number) candleData.get(size - 5).get(4)).doubleValue();
            double last = ((Number) candleData.get(size - 1).get(4)).doubleValue();
            double change = (last - first) / first;

            // FIXED: Extract confidence calculation to avoid duplication
            if (Math.abs(change) > 0.002) {
                String direction = change > 0 ? "BULLISH" : "BEARISH";
                prediction.setPredictedDirection(direction);

                // Single confidence calculation
                BigDecimal confidence = bd(String.valueOf(Math.min(0.9, 0.6 + Math.abs(change) * 10)));
                prediction.setConfidence(confidence);
            } else {
                prediction.setPredictedDirection("NEUTRAL");
                prediction.setConfidence(bd("0.5"));
            }
        }

        return prediction;
    }

    // FIXED: Update method calls to match new signature
    private MLPrediction createTimeframePrediction(IntraDayCandleData candles, String modelType, double weight) {
        if (candles == null || candles.getCandles() == null || candles.getCandles().isEmpty()) {
            return null;
        }
        MLPrediction prediction = new MLPrediction();
        prediction.setId(UUID.randomUUID().toString());
        prediction.setModelType(modelType);
        prediction.setCreatedAt(Instant.now());

        List<List<Object>> candleData = candles.getCandles();
        int size = candleData.size();
        if (size >= 5) {
            double first = ((Number) candleData.get(size - 5).get(4)).doubleValue();
            double last = ((Number) candleData.get(size - 1).get(4)).doubleValue();
            double change = (last - first) / first;
            if (Math.abs(change) > 0.002) {
                String direction = change > 0 ? "BULLISH" : "BEARISH";
                prediction.setPredictedDirection(direction);
                // Now include 'weight' in confidence calculation
                BigDecimal confidence = bd(String.valueOf(Math.min(0.9, (0.6 + Math.abs(change) * 10) * weight)));
                prediction.setConfidence(confidence);
            } else {
                prediction.setPredictedDirection("NEUTRAL");
                prediction.setConfidence(bd("0.5"));
            }
        }
        return prediction;
    }


    private double calculateWeightedPrediction(List<MLPrediction> predictions,
                                               Map<String, Double> weights) {
        double weightedSum = 0.0;
        double totalWeight = 0.0;

        for (MLPrediction pred : predictions) {
            double weight = weights.getOrDefault(pred.getModelType(), 1.0);
            double score = mapDirectionToScore(pred.getPredictedDirection());
            double confidence = pred.getConfidence().doubleValue();

            weightedSum += score * weight * confidence;
            totalWeight += weight * confidence;
        }

        return totalWeight > 0 ? weightedSum / totalWeight : 0.0;
    }

    private BigDecimal calculateEnsembleConfidence(List<MLPrediction> predictions) {
        if (predictions.isEmpty()) return bd("0.0");

        // Calculate confidence as agreement level + average individual confidence
        Map<String, Integer> directionVotes = new HashMap<>();
        double avgConfidence = 0.0;

        for (MLPrediction pred : predictions) {
            String dir = pred.getPredictedDirection();
            directionVotes.put(dir, directionVotes.getOrDefault(dir, 0) + 1);
            avgConfidence += pred.getConfidence().doubleValue();
        }

        avgConfidence /= predictions.size();

        // Agreement bonus
        int maxVotes = Collections.max(directionVotes.values());
        double agreement = (double) maxVotes / predictions.size();

        double finalConfidence = (avgConfidence * 0.7) + (agreement * 0.3);
        return bd(String.valueOf(Math.min(0.95, finalConfidence)));
    }

    private double mapDirectionToScore(String direction) {
        return switch (direction) {
            case "BULLISH" -> 1.0;
            case "BEARISH" -> -1.0;
            case "NEUTRAL" -> 0.0;
            default -> 0.0;
        };
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
