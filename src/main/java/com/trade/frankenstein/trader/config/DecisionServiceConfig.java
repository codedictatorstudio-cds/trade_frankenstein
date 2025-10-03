package com.trade.frankenstein.trader.config;

import com.trade.frankenstein.trader.enums.StrategyName;
import com.trade.frankenstein.trader.model.dto.StrategyWeights;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for DecisionService parameter optimization.
 */
@Getter
@Setter
@Component
@ConfigurationProperties("trading.decision")
public class DecisionServiceConfig {

    private boolean enableAdaptiveParameters = true;
    private Duration parameterAdjustmentWindow = Duration.ofDays(7);
    private double minAccuracyForParameterBoost = 0.65;
    private Map<StrategyName, StrategyWeights> strategyWeights = new HashMap<>();
}
