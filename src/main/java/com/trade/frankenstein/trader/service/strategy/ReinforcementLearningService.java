package com.trade.frankenstein.trader.service.strategy;

import com.trade.frankenstein.trader.enums.ActionType;
import com.trade.frankenstein.trader.model.documents.AlternativeDataSignal;
import com.trade.frankenstein.trader.model.documents.MarketMicrostructure;
import com.trade.frankenstein.trader.model.documents.RLAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class ReinforcementLearningService {

    private final Map<String, RLAction> recentActions = new HashMap<>();

    public RLAction recommendAction(StrategyService.EnhancedIndicators indicators,
                                    MarketMicrostructure microStructure,
                                    AlternativeDataSignal altData) {
        try {
            RLAction action = new RLAction();
            action.setId(UUID.randomUUID().toString());
            action.setAgentType("PPO");
            action.setCreatedAt(Instant.now());

            // Build state vector for RL agent
            Map<String, Object> state = buildStateVector(indicators, microStructure, altData);
            action.setState(state);

            // Simulate RL decision-making - replace with actual trained model
            ActionType recommendedAction = decideAction(state);
            action.setActionType(recommendedAction);

            // Calculate confidence based on state quality
            double confidence = calculateActionConfidence(state);
            action.setConfidence(bd(String.valueOf(confidence)));

            // Estimate expected reward
            double expectedReward = estimateReward(state, recommendedAction);
            action.setExpectedReward(bd(String.valueOf(expectedReward)));

            // Set optimal parameters
            Map<String, Double> parameters = new HashMap<>();
            parameters.put("optimal_delta_min", 0.3);
            parameters.put("optimal_delta_max", 0.7);
            parameters.put("optimal_stop_loss", 0.2);
            parameters.put("optimal_take_profit", 0.35);
            action.setParameters(parameters);

            return action;
        } catch (Exception e) {
            log.error("Failed to recommend RL action: {}", e.getMessage());
            return null;
        }
    }

    public void recordAction(RLAction action, String instrumentKey) {
        try {
            recentActions.put(instrumentKey, action);
            log.info("Recorded RL action for feedback: {} on {}", action.getActionType(), instrumentKey);
            // Implementation would store for model retraining
        } catch (Exception e) {
            log.error("Failed to record RL action: {}", e.getMessage());
        }
    }

    public BigDecimal getAverageRecentConfidence() {
        try {
            if (recentActions.isEmpty()) return bd("0.5");

            double avgConfidence = recentActions.values().stream()
                    .mapToDouble(action -> action.getConfidence().doubleValue())
                    .average()
                    .orElse(0.5);

            return bd(String.valueOf(avgConfidence));
        } catch (Exception e) {
            log.error("Failed to calculate average RL confidence: {}", e.getMessage());
            return bd("0.5");
        }
    }

    private Map<String, Object> buildStateVector(StrategyService.EnhancedIndicators indicators,
                                                 MarketMicrostructure microStructure,
                                                 AlternativeDataSignal altData) {
        Map<String, Object> state = new HashMap<>();

        // Technical indicators
        if (indicators != null && indicators.getTraditional() != null) {
            StrategyService.Ind ind = indicators.getTraditional();
            state.put("rsi", ind.rsi != null ? ind.rsi.doubleValue() : 50.0);
            state.put("adx", ind.adx != null ? ind.adx.doubleValue() : 20.0);
            state.put("atr_pct", ind.atrPct != null ? ind.atrPct.doubleValue() : 1.0);

            if (ind.ema20 != null && ind.ema50 != null) {
                state.put("ema_ratio", ind.ema20.doubleValue() / ind.ema50.doubleValue());
            }
        }

        // ML features
        if (indicators != null) {
            state.put("ml_momentum", indicators.getMlMomentumScore() != null ?
                    indicators.getMlMomentumScore().doubleValue() : 0.0);
            state.put("ml_trend_strength", indicators.getMlTrendStrength() != null ?
                    indicators.getMlTrendStrength().doubleValue() : 50.0);
        }

        // Microstructure
        if (microStructure != null) {
            state.put("liquidity_score", microStructure.getLiquidityScore().doubleValue());
            state.put("imbalance", microStructure.getImbalance().doubleValue());
        }

        // Alternative data
        if (altData != null) {
            state.put("alt_signal_strength", altData.getStrength().doubleValue());
        }

        // Time features
        int hour = java.time.LocalTime.now().getHour();
        state.put("hour", hour);
        state.put("is_opening", hour >= 9 && hour <= 10 ? 1.0 : 0.0);
        state.put("is_closing", hour >= 14 && hour <= 15 ? 1.0 : 0.0);

        return state;
    }

    private ActionType decideAction(Map<String, Object> state) {
        // Simulate RL decision logic - replace with actual trained model
        double rsi = (Double) state.getOrDefault("rsi", 50.0);
        double trend = (Double) state.getOrDefault("ml_trend_strength", 50.0);
        double momentum = (Double) state.getOrDefault("ml_momentum", 0.0);

        if (trend > 60 && momentum > 0.02) {
            return ActionType.LONG_CALL;
        } else if (trend < 40 && momentum < -0.02) {
            return ActionType.LONG_PUT;
        } else if (rsi > 70 || rsi < 30) {
            return ActionType.LONG_STRADDLE;
        } else {
            return ActionType.HOLD;
        }
    }

    private double calculateActionConfidence(Map<String, Object> state) {
        // Simulate confidence calculation based on state consistency
        double baseConfidence = 0.6;

        Double rsi = (Double) state.get("rsi");
        if (rsi != null && (rsi > 70 || rsi < 30)) {
            baseConfidence += 0.15; // High confidence in extreme RSI
        }

        Double trendStrength = (Double) state.get("ml_trend_strength");
        if (trendStrength != null && (trendStrength > 70 || trendStrength < 30)) {
            baseConfidence += 0.1;
        }

        Double liquidityScore = (Double) state.get("liquidity_score");
        if (liquidityScore != null && liquidityScore > 0.7) {
            baseConfidence += 0.1; // Good liquidity increases confidence
        }

        return Math.min(0.95, baseConfidence);
    }

    private double estimateReward(Map<String, Object> state, ActionType action) {
        // Simulate reward estimation - replace with actual model
        double baseReward = 0.05;

        Double momentum = (Double) state.getOrDefault("ml_momentum", 0.0);
        if (action == ActionType.LONG_CALL && momentum > 0.02) {
            baseReward += 0.03;
        } else if (action == ActionType.LONG_PUT && momentum < -0.02) {
            baseReward += 0.03;
        }

        return baseReward;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
