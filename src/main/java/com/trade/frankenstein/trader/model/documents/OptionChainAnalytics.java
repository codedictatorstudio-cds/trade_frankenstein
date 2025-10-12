package com.trade.frankenstein.trader.model.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

@Document("option_chain_analytics")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionChainAnalytics {
    @Id
    private String id;

    @Indexed
    private String underlyingKey;

    @Indexed
    private LocalDate expiry;

    @Indexed
    private Instant calculatedAt;

    private BigDecimal maxPain;
    private BigDecimal totalCallOi;
    private BigDecimal totalPutOi;
    private BigDecimal oiPcr;
    private BigDecimal volumePcr;
    private BigDecimal ivPercentile;
    private BigDecimal ivSkew;
    private BigDecimal gammaExposure;
    private BigDecimal deltaNeutralLevel;

    // OI Change Analytics
    private Map<String, Long> oiChanges; // strike -> OI change
    private Map<String, Integer> oiChangeRanks; // strike -> rank

    // Greeks Summary
    private BigDecimal totalGamma;
    private BigDecimal totalDelta;
    private BigDecimal totalTheta;
    private BigDecimal totalVega;

    // Market Microstructure
    private BigDecimal bidAskSpread;
    private BigDecimal liquidityScore;
    private Integer activeStrikes;

    // Volatility Metrics
    private BigDecimal atmVolatility;
    private BigDecimal volSkew;
    private BigDecimal volConvexity;

    // Risk Metrics
    private BigDecimal varRisk;
    private BigDecimal tailRisk;
    private BigDecimal correlationRisk;

    // Market Regime Indicators
    private String marketRegime; // BULLISH, BEARISH, NEUTRAL, VOLATILE
    private BigDecimal regimeConfidence;
    private BigDecimal trendStrength;

    // Liquidity Depth
    private Map<String, BigDecimal> strikeLiquidity;
    private BigDecimal avgLiquidity;
    private BigDecimal liquidityConcentration;

    // Advanced Greeks
    private BigDecimal charm; // Delta decay
    private BigDecimal vomma; // Vega convexity
    private BigDecimal vanna; // Delta-Vega cross

    // Flow Analysis
    private BigDecimal institutionalFlow;
    private BigDecimal retailFlow;
    private BigDecimal flowRatio;

    // Price Discovery
    private BigDecimal priceDiscoveryScore;
    private BigDecimal informationContent;
    private BigDecimal microstructureNoise;

    // Historical Context
    private BigDecimal percentileRank; // Where current metrics rank historically
    private BigDecimal zScore; // Standard deviations from mean
    private Integer observationCount; // Number of data points used

    // Quality Metrics
    private BigDecimal dataQuality;
    private Integer missingDataPoints;
    private BigDecimal calculationAccuracy;

    public boolean isHighQuality() {
        return dataQuality != null && dataQuality.compareTo(new BigDecimal("0.8")) >= 0;
    }

    public boolean isStaleData() {
        return calculatedAt != null &&
                calculatedAt.isBefore(Instant.now().minus(5, java.time.temporal.ChronoUnit.MINUTES));
    }

    public boolean hasSignificantOiChange() {
        return oiChanges != null && oiChanges.values().stream()
                .anyMatch(change -> Math.abs(change) > 1000);
    }
}
