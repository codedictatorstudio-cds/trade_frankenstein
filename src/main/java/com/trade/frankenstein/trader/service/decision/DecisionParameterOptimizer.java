package com.trade.frankenstein.trader.service.decision;

import com.trade.frankenstein.trader.config.DecisionServiceConfig;
import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.enums.StrategyName;
import com.trade.frankenstein.trader.model.dto.StrategyWeights;
import com.trade.frankenstein.trader.repo.documents.TradeRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service that periodically analyzes decision performance and adjusts
 * DecisionService parameters to optimize decision quality over time.
 */
@Slf4j
@Service
public class DecisionParameterOptimizer {

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private TradeRepo tradeRepo;

    @Autowired
    private DecisionServiceConfig config;

    @Autowired
    private FastStateStore fast;

    /**
     * Runs daily at midnight to optimize decision parameters based on
     * recent performance metrics.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void optimizeDaily() {
        if (!config.isEnableAdaptiveParameters()) {
            log.info("Adaptive parameter optimization disabled.");
            return;
        }
        log.info("Starting daily decision parameter optimization...");
        Instant windowStart = Instant.now().minus(config.getParameterAdjustmentWindow());
        try {
            // 1) Compute overall accuracy for each strategy
            Map<StrategyName, Double> accuracies = computeStrategyAccuracies(windowStart);

            // 2) Identify underperforming strategies
            List<StrategyName> underperformers = accuracies.entrySet().stream()
                    .filter(e -> e.getValue() < config.getMinAccuracyForParameterBoost())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            // 3) Adjust weights for underperformers
            for (StrategyName strategy : underperformers) {
                StrategyWeights current = config.getStrategyWeights().get(strategy);
                StrategyWeights adjusted = boostWeights(current);
                config.getStrategyWeights().put(strategy, adjusted);
                log.info("Boosted weights for {}: {}", strategy, adjusted);
            }

            // 4) Schedule A/B tests for new parameter sets
            scheduleABTests(underperformers);

        } catch (Exception e) {
            log.error("Decision parameter optimization failed", e);
        }
    }

    private Map<StrategyName, Double> computeStrategyAccuracies(Instant since) {
        Map<StrategyName, Double> accuracies = new HashMap<>();
        for (StrategyName strategy : StrategyName.values()) {
            if (!strategy.isRiskStrategy()) {
                Integer acc = decisionService.computeEnhancedAccuracy(strategy,
                        (int) config.getParameterAdjustmentWindow().toHours());
                accuracies.put(strategy, acc == null ? 1.0 : acc / 100.0);
            }
        }
        return accuracies;
    }

    private StrategyWeights boostWeights(StrategyWeights w) {
        // Increase trend weight by 10%, decrease sentiment weight
        double newWs = Math.min(1.0, w.getWs() * 1.1);
        double newWr = Math.max(0.0, w.getWr() * 0.9);
        double newWm = Math.max(0.0, 1.0 - newWs - newWr);
        return new StrategyWeights(newWs, newWr, newWm);
    }

    private void scheduleABTests(List<StrategyName> strategies) {
        for (StrategyName strategy : strategies) {
            // For each underperforming strategy, create two parameter variants
            StrategyWeights original = config.getStrategyWeights().get(strategy);
            StrategyWeights variantA = new StrategyWeights(
                    clamp(original.getWs() + 0.05),
                    clamp(original.getWr() - 0.05),
                    clamp(original.getWm())
            );
            StrategyWeights variantB = new StrategyWeights(
                    clamp(original.getWs() - 0.05),
                    clamp(original.getWr() + 0.05),
                    clamp(original.getWm())
            );
            // A/B test registration (stored in FastStateStore for tracking results)
            String baseKey = "abtest:" + strategy.name() + ":" + Instant.now().toEpochMilli();
            fast.put(baseKey + ":A", variantA.toString(), Duration.ofDays(7));
            fast.put(baseKey + ":B", variantB.toString(), Duration.ofDays(7));
            log.info("Scheduled A/B test for {}: A={} B={}", strategy, variantA, variantB);
        }
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
