package com.trade.frankenstein.trader.service.strategy;

import com.trade.frankenstein.trader.enums.Direction;
import com.trade.frankenstein.trader.model.documents.PatternMatch;
import com.upstox.api.IntraDayCandleData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class PatternRecognitionService {

    private final AtomicReference<Double> avgPatternStrength = new AtomicReference<>(0.65);

    public List<PatternMatch> detectPatterns(IntraDayCandleData candles, int lookbackMinutes) {
        List<PatternMatch> patterns = new ArrayList<>();

        if (candles == null || candles.getCandles() == null || candles.getCandles().size() < 20) {
            return patterns;
        }

        try {
            List<List<Object>> candleData = candles.getCandles();

            // Detect various patterns
            PatternMatch support = detectSupportResistance(candleData, true);
            if (support != null) patterns.add(support);

            PatternMatch resistance = detectSupportResistance(candleData, false);
            if (resistance != null) patterns.add(resistance);

            PatternMatch triangle = detectTrianglePattern(candleData);
            if (triangle != null) patterns.add(triangle);

            PatternMatch breakout = detectBreakoutPattern(candleData);
            if (breakout != null) patterns.add(breakout);

            PatternMatch reversal = detectReversalPattern(candleData);
            if (reversal != null) patterns.add(reversal);

            // Update average pattern strength
            if (!patterns.isEmpty()) {
                double avgStrength = patterns.stream()
                        .mapToDouble(p -> p.getConfidence().doubleValue())
                        .average()
                        .orElse(0.5);
                updateAveragePatternStrength(avgStrength);
            }

        } catch (Exception e) {
            log.error("Failed to detect patterns: {}", e.getMessage());
        }

        return patterns;
    }

    public Double getAveragePatternStrength() {
        return avgPatternStrength.get();
    }

    private PatternMatch detectSupportResistance(List<List<Object>> candles, boolean isSupport) {
        try {
            int size = candles.size();
            if (size < 20) return null;

            List<Double> prices = new ArrayList<>();
            List<Double> volumes = new ArrayList<>();

            for (int i = size - 20; i < size; i++) {
                double price = isSupport ?
                        ((Number) candles.get(i).get(3)).doubleValue() : // Low for support
                        ((Number) candles.get(i).get(2)).doubleValue();  // High for resistance
                double volume = ((Number) candles.get(i).get(5)).doubleValue();
                prices.add(price);
                volumes.add(volume);
            }

            // Find price level that was tested multiple times
            Map<Double, Integer> priceLevelTests = new HashMap<>();
            double tolerance = 0.002; // 0.2% tolerance

            for (double price : prices) {
                boolean foundLevel = false;
                for (Map.Entry<Double, Integer> entry : priceLevelTests.entrySet()) {
                    if (Math.abs(price - entry.getKey()) / entry.getKey() < tolerance) {
                        priceLevelTests.put(entry.getKey(), entry.getValue() + 1);
                        foundLevel = true;
                        break;
                    }
                }
                if (!foundLevel) {
                    priceLevelTests.put(price, 1);
                }
            }

            // Find the most tested level
            Map.Entry<Double, Integer> mostTested = priceLevelTests.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);

            if (mostTested != null && mostTested.getValue() >= 3) {
                PatternMatch pattern = new PatternMatch();
                pattern.setId(UUID.randomUUID().toString());
                pattern.setPatternName(isSupport ? "SUPPORT_LEVEL" : "RESISTANCE_LEVEL");
                pattern.setDetectedAt(Instant.now());
                pattern.setDetectionMethod("PRICE_LEVEL_ANALYSIS");

                double level = mostTested.getKey();
                if (isSupport) {
                    pattern.setSupportLevel(bd(String.valueOf(level)));
                    pattern.setExpectedDirection(Direction.UP);
                } else {
                    pattern.setResistanceLevel(bd(String.valueOf(level)));
                    pattern.setExpectedDirection(Direction.DOWN);
                }

                // Confidence based on number of tests and volume
                double confidence = 0.5 + (mostTested.getValue() - 2) * 0.15;
                confidence = Math.min(0.9, confidence);
                pattern.setConfidence(bd(String.valueOf(confidence)));

                pattern.setExpectedDuration(Duration.ofMinutes(30));

                return pattern;
            }

        } catch (Exception e) {
            log.error("Failed to detect support/resistance: {}", e.getMessage());
        }

        return null;
    }

    private PatternMatch detectTrianglePattern(List<List<Object>> candles) {
        try {
            int size = candles.size();
            if (size < 15) return null;

            List<Double> highs = new ArrayList<>();
            List<Double> lows = new ArrayList<>();

            for (int i = size - 15; i < size; i++) {
                highs.add(((Number) candles.get(i).get(2)).doubleValue());
                lows.add(((Number) candles.get(i).get(3)).doubleValue());
            }

            // Simple triangle detection: converging highs and lows
            double firstHigh = highs.get(0);
            double lastHigh = highs.get(highs.size() - 1);
            double firstLow = lows.get(0);
            double lastLow = lows.get(lows.size() - 1);

            boolean convergingHighs = Math.abs(lastHigh - firstHigh) < Math.abs(firstHigh) * 0.01;
            boolean convergingLows = Math.abs(lastLow - firstLow) < Math.abs(firstLow) * 0.01;

            if (convergingHighs || convergingLows) {
                PatternMatch pattern = new PatternMatch();
                pattern.setId(UUID.randomUUID().toString());
                pattern.setPatternName("TRIANGLE");
                pattern.setDetectedAt(Instant.now());
                pattern.setDetectionMethod("CONVERGENCE_ANALYSIS");
                pattern.setExpectedDirection(Direction.UNKNOWN); // Triangle can break either way
                pattern.setConfidence(bd("0.7"));
                pattern.setExpectedDuration(Duration.ofMinutes(20));

                return pattern;
            }

        } catch (Exception e) {
            log.error("Failed to detect triangle pattern: {}", e.getMessage());
        }

        return null;
    }

    private PatternMatch detectBreakoutPattern(List<List<Object>> candles) {
        try {
            int size = candles.size();
            if (size < 10) return null;

            // Look for sudden volume and price movement
            double recentVolume = 0, pastVolume = 0;
            double recentClose = ((Number) candles.get(size - 1).get(4)).doubleValue();
            double pastClose = ((Number) candles.get(size - 6).get(4)).doubleValue();

            for (int i = size - 3; i < size; i++) {
                recentVolume += ((Number) candles.get(i).get(5)).doubleValue();
            }

            for (int i = size - 9; i < size - 6; i++) {
                pastVolume += ((Number) candles.get(i).get(5)).doubleValue();
            }

            double volumeRatio = recentVolume / Math.max(pastVolume, 1.0);
            double priceChange = Math.abs(recentClose - pastClose) / pastClose;

            if (volumeRatio > 1.5 && priceChange > 0.005) { // 50% volume increase + 0.5% price move
                PatternMatch pattern = new PatternMatch();
                pattern.setId(UUID.randomUUID().toString());
                pattern.setPatternName("BREAKOUT");
                pattern.setDetectedAt(Instant.now());
                pattern.setDetectionMethod("VOLUME_PRICE_ANALYSIS");
                pattern.setExpectedDirection(recentClose > pastClose ? Direction.UP : Direction.DOWN);

                double confidence = Math.min(0.85, 0.6 + (volumeRatio - 1.5) * 0.1 + priceChange * 10);
                pattern.setConfidence(bd(String.valueOf(confidence)));
                pattern.setExpectedDuration(Duration.ofMinutes(25));

                return pattern;
            }

        } catch (Exception e) {
            log.error("Failed to detect breakout pattern: {}", e.getMessage());
        }

        return null;
    }

    private PatternMatch detectReversalPattern(List<List<Object>> candles) {
        try {
            int size = candles.size();
            if (size < 8) return null;

            // Look for reversal candlestick patterns
            List<Object> current = candles.get(size - 1);
            List<Object> previous = candles.get(size - 2);

            double currentOpen = ((Number) current.get(1)).doubleValue();
            double currentClose = ((Number) current.get(4)).doubleValue();
            double currentHigh = ((Number) current.get(2)).doubleValue();
            double currentLow = ((Number) current.get(3)).doubleValue();

            double prevOpen = ((Number) previous.get(1)).doubleValue();
            double prevClose = ((Number) previous.get(4)).doubleValue();

            // Detect hammer/doji patterns
            double bodySize = Math.abs(currentClose - currentOpen);
            double totalRange = currentHigh - currentLow;
            double upperShadow = currentHigh - Math.max(currentOpen, currentClose);
            double lowerShadow = Math.min(currentOpen, currentClose) - currentLow;

            if (totalRange > 0 && bodySize / totalRange < 0.3) { // Small body
                boolean isHammer = lowerShadow > bodySize * 2 && upperShadow < bodySize;
                boolean isDoji = bodySize / totalRange < 0.1;

                if (isHammer || isDoji) {
                    PatternMatch pattern = new PatternMatch();
                    pattern.setId(UUID.randomUUID().toString());
                    pattern.setPatternName(isHammer ? "HAMMER" : "DOJI");
                    pattern.setDetectedAt(Instant.now());
                    pattern.setDetectionMethod("CANDLESTICK_ANALYSIS");

                    // Determine direction based on context
                    boolean wasDowntrend = prevClose < prevOpen;
                    pattern.setExpectedDirection(wasDowntrend ? Direction.UP : Direction.DOWN);

                    double confidence = 0.6 + (isDoji ? 0.1 : 0.0) + (isHammer ? 0.15 : 0.0);
                    pattern.setConfidence(bd(String.valueOf(Math.min(0.85, confidence))));
                    pattern.setExpectedDuration(Duration.ofMinutes(15));

                    return pattern;
                }
            }

        } catch (Exception e) {
            log.error("Failed to detect reversal pattern: {}", e.getMessage());
        }

        return null;
    }

    private void updateAveragePatternStrength(double newStrength) {
        double current = avgPatternStrength.get();
        double updated = current * 0.8 + newStrength * 0.2; // Exponential moving average
        avgPatternStrength.set(updated);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
