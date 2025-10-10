package com.trade.frankenstein.trader.service.strategy;

import com.trade.frankenstein.trader.dto.MLFeatures;
import com.trade.frankenstein.trader.dto.MLRiskAssessment;
import com.trade.frankenstein.trader.dto.OptimalPositionSize;
import com.trade.frankenstein.trader.model.documents.MLPrediction;
import com.trade.frankenstein.trader.model.documents.RLAction;
import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import com.trade.frankenstein.trader.model.documents.VolatilityPrediction;
import com.upstox.api.IntraDayCandleData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

@Service
@Slf4j
public class MLPredictionService {

    public MLRiskAssessment assessRiskConditions() {
        try {
            MLRiskAssessment assessment = new MLRiskAssessment();

            // Simulate ML risk assessment - replace with actual ML model
            Map<String, BigDecimal> riskFactors = new HashMap<>();
            riskFactors.put("market_volatility", bd("0.3"));
            riskFactors.put("correlation_risk", bd("0.2"));
            riskFactors.put("liquidity_risk", bd("0.1"));
            riskFactors.put("model_uncertainty", bd("0.15"));

            BigDecimal totalRisk = riskFactors.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assessment.setRiskScore(totalRisk);
            assessment.setRiskFactors(riskFactors);
            assessment.setConfidenceLevel(bd("0.85"));
            assessment.setRecommendation(totalRisk.compareTo(bd("0.7")) > 0 ? "REDUCE_EXPOSURE" : "NORMAL");

            return assessment;
        } catch (Exception e) {
            log.error("Failed to assess ML risk conditions: {}", e.getMessage());
            return null;
        }
    }

    public MLPrediction predictTrend(int horizonMinutes) {
        try {
            MLPrediction prediction = new MLPrediction();
            prediction.setId(UUID.randomUUID().toString());
            prediction.setSymbol("NIFTY");
            prediction.setModelType("LSTM_ENSEMBLE");
            prediction.setPredictionHorizon(Duration.ofMinutes(horizonMinutes));

            // Simulate ML prediction - replace with actual model
            Random random = new Random();
            double confidence = 0.6 + random.nextDouble() * 0.3; // 0.6 to 0.9

            String[] directions = {"BULLISH", "BEARISH", "NEUTRAL"};
            String direction = directions[random.nextInt(directions.length)];

            prediction.setPredictedDirection(direction);
            prediction.setConfidence(bd(String.valueOf(confidence)));

            List<String> features = Arrays.asList("price_momentum", "volume_profile",
                    "volatility_surface", "options_flow");
            prediction.setFeatures(features);

            prediction.setCreatedAt(java.time.Instant.now());
            prediction.setExpiresAt(java.time.Instant.now().plus(Duration.ofMinutes(horizonMinutes)));

            return prediction;
        } catch (Exception e) {
            log.error("Failed to predict trend: {}", e.getMessage());
            return null;
        }
    }

    public MLFeatures extractFeatures(IntraDayCandleData candles) {
        try {
            MLFeatures features = new MLFeatures();

            if (candles == null || candles.getCandles() == null || candles.getCandles().isEmpty()) {
                return features;
            }

            List<List<Object>> candleData = candles.getCandles();
            int size = candleData.size();

            // Calculate momentum score (simplified)
            if (size >= 10) {
                double recentAvg = 0, pastAvg = 0;
                for (int i = size - 5; i < size; i++) {
                    recentAvg += ((Number) candleData.get(i).get(4)).doubleValue();
                }
                for (int i = size - 10; i < size - 5; i++) {
                    pastAvg += ((Number) candleData.get(i).get(4)).doubleValue();
                }
                recentAvg /= 5;
                pastAvg /= 5;

                features.setMomentumScore(bd(String.valueOf((recentAvg - pastAvg) / pastAvg)));
            }

            // Determine volatility regime
            double volatility = calculateVolatility(candleData);
            if (volatility < 0.01) {
                features.setVolatilityRegime("LOW");
            } else if (volatility > 0.03) {
                features.setVolatilityRegime("HIGH");
            } else {
                features.setVolatilityRegime("MEDIUM");
            }

            // Trend strength (simplified RSI-like calculation)
            features.setTrendStrength(bd(String.valueOf(Math.random() * 100))); // Replace with actual calculation

            // Seasonal adjustment (time-based)
            int hour = java.time.LocalTime.now().getHour();
            double seasonal = 1.0;
            if (hour >= 9 && hour <= 11) seasonal = 1.1; // Morning strength
            if (hour >= 14 && hour <= 15) seasonal = 1.05; // Afternoon pickup
            features.setSeasonalAdjustment(bd(String.valueOf(seasonal)));

            return features;
        } catch (Exception e) {
            log.error("Failed to extract ML features: {}", e.getMessage());
            return new MLFeatures();
        }
    }

    public OptimalPositionSize calculateOptimalPositionSize(RiskSnapshot risk,
                                                            VolatilityPrediction volPred,
                                                            StrategyService.EnhancedIndicators indicators,
                                                            RLAction rlAction) {
        try {
            OptimalPositionSize optimal = new OptimalPositionSize();

            // Base calculation on risk budget
            int baseLots = 1;
            if (risk != null && risk.getLotsCap() != null) {
                baseLots = Math.min(3, risk.getLotsCap()); // Conservative max
            }

            // Adjust for volatility
            if (volPred != null && volPred.getConfidence().compareTo(bd("0.7")) >= 0) {
                double volLevel = volPred.getPredictedVolatility().doubleValue();
                if (volLevel > 25.0) {
                    baseLots = Math.max(1, baseLots - 1); // Reduce in high vol
                } else if (volLevel < 15.0) {
                    baseLots = Math.min(baseLots + 1, 3); // Increase in low vol
                }
            }

            // Adjust for ML confidence
            if (indicators != null && indicators.getMlTrendStrength() != null) {
                double trendStrength = indicators.getMlTrendStrength().doubleValue();
                if (trendStrength > 70.0) {
                    baseLots = Math.min(baseLots + 1, 3);
                } else if (trendStrength < 30.0) {
                    baseLots = Math.max(1, baseLots - 1);
                }
            }

            // RL adjustment
            if (rlAction != null && rlAction.getConfidence().compareTo(bd("0.8")) >= 0) {
                baseLots = Math.min(baseLots + 1, 3);
            }

            optimal.setRecommendedLots(baseLots);
            optimal.setMaxLots(Math.min(5, baseLots * 2));
            optimal.setConfidence(bd("0.75"));
            optimal.setMethodology("ML_KELLY_HYBRID");

            return optimal;
        } catch (Exception e) {
            log.error("Failed to calculate optimal position size: {}", e.getMessage());
            return null;
        }
    }

    public void recordStrategyMetrics(Map<String, Object> metrics) {
        try {
            // Store metrics for ML model retraining
            log.info("Recording strategy metrics for ML feedback: {}", metrics);
            // Implementation would store in database for model improvement
        } catch (Exception e) {
            log.error("Failed to record strategy metrics: {}", e.getMessage());
        }
    }

    private double calculateVolatility(List<List<Object>> candles) {
        if (candles.size() < 2) return 0.0;

        double sumSquaredReturns = 0.0;
        int count = 0;

        for (int i = 1; i < candles.size(); i++) {
            double prev = ((Number) candles.get(i - 1).get(4)).doubleValue();
            double curr = ((Number) candles.get(i).get(4)).doubleValue();
            if (prev > 0) {
                double ret = Math.log(curr / prev);
                sumSquaredReturns += ret * ret;
                count++;
            }
        }

        return count > 0 ? Math.sqrt(sumSquaredReturns / count) : 0.0;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
