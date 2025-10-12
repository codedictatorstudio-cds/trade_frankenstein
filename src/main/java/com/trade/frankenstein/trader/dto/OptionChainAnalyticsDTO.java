package com.trade.frankenstein.trader.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionChainAnalyticsDTO {
    private String underlyingKey;
    private LocalDate expiry;
    private Instant calculatedAt;

    // Core Metrics
    private BigDecimal maxPain;
    private BigDecimal oiPcr;
    private BigDecimal volumePcr;
    private BigDecimal ivPercentile;
    private BigDecimal ivSkew;
    private BigDecimal gammaExposure;
    private BigDecimal deltaNeutralLevel;

    // Enhanced analytics
    private List<OiChangeDTO> topOiIncreases;
    private List<OiChangeDTO> topOiDecreases;
    private GreeksSummaryDTO greeksSummary;
    private VolatilityMetricsDTO volatilityMetrics;
    private LiquidityMetricsDTO liquidityMetrics;
    private RiskMetricsDTO riskMetrics;
    private FlowAnalysisDTO flowAnalysis;
    private MarketRegimeDTO marketRegime;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OiChangeDTO {
        private BigDecimal strike;
        private String optionType;
        private Long oiChange;
        private Long previousOi;
        private Long currentOi;
        private Integer rank;
        private BigDecimal percentageChange;
        private String flowDirection; // BULLISH, BEARISH, NEUTRAL
        private BigDecimal impactScore; // 0-100 impact on underlying
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GreeksSummaryDTO {
        private BigDecimal totalDelta;
        private BigDecimal totalGamma;
        private BigDecimal totalTheta;
        private BigDecimal totalVega;
        private BigDecimal totalRho;

        private BigDecimal netGammaExposure;
        private BigDecimal gammaFlipLevel;
        private BigDecimal deltaAdjustment;

        // Risk-adjusted Greeks
        private BigDecimal effectiveDelta;
        private BigDecimal dollarGamma;
        private BigDecimal thetaDecay;
        private BigDecimal vegaRisk;

        // Portfolio Greeks
        private BigDecimal portfolioDelta;
        private BigDecimal portfolioGamma;
        private BigDecimal portfolioVega;

        // Greeks momentum
        private BigDecimal deltaChange;
        private BigDecimal gammaChange;
        private BigDecimal thetaChange;
        private BigDecimal vegaChange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VolatilityMetricsDTO {
        private BigDecimal atmIv;
        private BigDecimal ivSkew;
        private BigDecimal ivConvexity;
        private BigDecimal termStructure;
        private BigDecimal volOfVol;

        // Strike-wise IV data
        private Map<String, BigDecimal> strikeIvMap;
        private Map<String, BigDecimal> ivPercentileMap;

        // Volatility surface metrics
        private BigDecimal skew25Delta;
        private BigDecimal skew10Delta;
        private BigDecimal butterflySpread;

        // Volatility risk
        private BigDecimal vegaExposure;
        private BigDecimal volRisk;
        private BigDecimal correlationRisk;

        // Historical context
        private BigDecimal ivPercentileRank;
        private BigDecimal ivZScore;
        private Integer daysInRange;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LiquidityMetricsDTO {
        private BigDecimal avgBidAskSpread;
        private BigDecimal medianBidAskSpread;
        private Integer activeStrikes;
        private BigDecimal liquidityScore;
        private BigDecimal marketDepth;

        // Strike-wise liquidity
        private Map<String, BigDecimal> strikeLiquidity;
        private Map<String, BigDecimal> strikeSpread;
        private Map<String, Long> strikeVolume;

        // Liquidity concentration
        private BigDecimal liquidityConcentration;
        private List<String> liquidStrikes;
        private List<String> illiquidStrikes;

        // Market making metrics
        private BigDecimal effectiveSpread;
        private BigDecimal priceImpact;
        private BigDecimal resilienceScore;

        // Trading cost estimates
        private Map<String, BigDecimal> tradingCosts;
        private BigDecimal avgTradingCost;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskMetricsDTO {
        private BigDecimal varRisk;
        private BigDecimal tailRisk;
        private BigDecimal correlationRisk;
        private BigDecimal concentrationRisk;

        // Scenario analysis
        private Map<String, BigDecimal> scenarioExposures; // scenario -> P&L
        private BigDecimal worstCaseScenario;
        private BigDecimal bestCaseScenario;

        // Stress tests
        private BigDecimal stressVol;
        private BigDecimal stressMove;
        private BigDecimal maxDrawdown;

        // Risk-adjusted returns
        private BigDecimal sharpeRatio;
        private BigDecimal sortinoRatio;
        private BigDecimal calmarRatio;

        // Portfolio risk
        private BigDecimal betaRisk;
        private BigDecimal idiosyncraticRisk;
        private BigDecimal systemicRisk;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FlowAnalysisDTO {
        private BigDecimal institutionalFlow;
        private BigDecimal retailFlow;
        private BigDecimal flowRatio;
        private String dominantFlow; // INSTITUTIONAL, RETAIL, BALANCED

        // Flow direction
        private BigDecimal bullishFlow;
        private BigDecimal bearishFlow;
        private String netFlowDirection;

        // Smart money indicators
        private BigDecimal smartMoneyIndex;
        private BigDecimal unusualActivity;
        private List<String> unusualStrikes;

        // Order flow analysis
        private BigDecimal buyPressure;
        private BigDecimal sellPressure;
        private BigDecimal orderImbalance;

        // Block trades
        private Integer blockTradeCount;
        private BigDecimal blockTradeVolume;
        private BigDecimal avgBlockSize;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MarketRegimeDTO {
        private String currentRegime; // BULLISH, BEARISH, NEUTRAL, VOLATILE
        private BigDecimal regimeConfidence;
        private BigDecimal trendStrength;
        private Integer daysSinceChange;

        // Regime characteristics
        private BigDecimal volatilityLevel;
        private BigDecimal momentumStrength;
        private BigDecimal meanReversionTendency;

        // Probability forecasts
        private BigDecimal bullishProbability;
        private BigDecimal bearishProbability;
        private BigDecimal neutralProbability;
        private BigDecimal volatileProbability;

        // Historical context
        private Integer regimeHistoryDays;
        private BigDecimal avgRegimeDuration;
        private String previousRegime;
    }

    // Utility methods
    public boolean hasHighActivity() {
        return topOiIncreases != null && !topOiIncreases.isEmpty() &&
                topOiIncreases.stream().anyMatch(change ->
                        change.getOiChange() != null && Math.abs(change.getOiChange()) > 1000);
    }

    public boolean isBullishSignal() {
        return oiPcr != null && oiPcr.compareTo(new BigDecimal("0.7")) < 0;
    }

    public boolean isBearishSignal() {
        return oiPcr != null && oiPcr.compareTo(new BigDecimal("1.3")) > 0;
    }

    public boolean hasHighIv() {
        return volatilityMetrics != null && volatilityMetrics.getIvPercentileRank() != null &&
                volatilityMetrics.getIvPercentileRank().compareTo(new BigDecimal("80")) > 0;
    }

    public boolean isLiquid() {
        return liquidityMetrics != null && liquidityMetrics.getLiquidityScore() != null &&
                liquidityMetrics.getLiquidityScore().compareTo(new BigDecimal("0.7")) > 0;
    }

    public String getOverallSignal() {
        if (isBullishSignal()) return "BULLISH";
        if (isBearishSignal()) return "BEARISH";
        return "NEUTRAL";
    }
}
