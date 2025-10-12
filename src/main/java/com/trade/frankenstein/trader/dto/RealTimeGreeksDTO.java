package com.trade.frankenstein.trader.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealTimeGreeksDTO {
    private String instrumentKey;
    private BigDecimal strike;
    private String optionType;
    private Instant timestamp;
    private String underlyingKey;

    // Basic Greeks
    private BigDecimal delta;
    private BigDecimal gamma;
    private BigDecimal theta;
    private BigDecimal vega;
    private BigDecimal rho;
    private BigDecimal impliedVolatility;

    // Enhanced Greeks
    private BigDecimal charm; // Delta decay
    private BigDecimal vomma; // Vega convexity
    private BigDecimal vanna; // Delta-Vega cross
    private BigDecimal speed; // Gamma acceleration
    private BigDecimal zomma; // Gamma-Vol cross
    private BigDecimal color; // Gamma time decay

    // Market Data
    private BigDecimal lastPrice;
    private BigDecimal bidPrice;
    private BigDecimal askPrice;
    private BigDecimal midPrice;
    private Long volume;
    private Long openInterest;
    private Long dayVolume;

    // Derived Metrics
    private BigDecimal moneyness;
    private BigDecimal timeToExpiry;
    private BigDecimal intrinsicValue;
    private BigDecimal timeValue;
    private BigDecimal elasticity;

    // Risk Metrics
    private BigDecimal effectiveDelta;
    private BigDecimal dollarDelta;
    private BigDecimal dollarGamma;
    private BigDecimal dollarTheta;
    private BigDecimal dollarVega;
    private BigDecimal dollarRho;

    // Quality Indicators
    private BigDecimal bidAskSpread;
    private BigDecimal spreadPercentage;
    private Boolean isLiquid;
    private BigDecimal dataQuality;
    private String dataSource;

    // Model Information
    private String pricingModel;
    private BigDecimal modelError;
    private BigDecimal impliedVolError;
    private BigDecimal calibrationConfidence;

    // Historical Context
    private BigDecimal deltaChange24h;
    private BigDecimal gammaChange24h;
    private BigDecimal thetaChange24h;
    private BigDecimal vegaChange24h;
    private BigDecimal ivChange24h;
    private BigDecimal priceChange24h;

    // Greeks Percentile Rankings (0-100)
    private BigDecimal deltaPercentile;
    private BigDecimal gammaPercentile;
    private BigDecimal thetaPercentile;
    private BigDecimal vegaPercentile;
    private BigDecimal ivPercentile;

    // Advanced Analytics
    private BigDecimal probabilityOfProfit;
    private BigDecimal expectedMove;
    private BigDecimal breakEvenPoint;
    private BigDecimal maxRisk;
    private BigDecimal maxReward;

    // Portfolio Context
    private BigDecimal positionSize;
    private BigDecimal portfolioWeight;
    private BigDecimal marginRequirement;
    private BigDecimal capitalAllocation;

    // Alerts and Signals
    private Boolean hasAlerts;
    private String signalStrength; // WEAK, MODERATE, STRONG
    private String recommendedAction; // BUY, SELL, HOLD, CLOSE
    private BigDecimal confidenceScore;

    // Market Microstructure
    private BigDecimal orderBookImbalance;
    private BigDecimal effectiveSpread;
    private BigDecimal priceImpact;
    private Integer tradesCount;
    private BigDecimal avgTradeSize;

    // Utility methods
    public boolean isInTheMoney() {
        return intrinsicValue != null && intrinsicValue.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNearExpiry() {
        return timeToExpiry != null && timeToExpiry.compareTo(new BigDecimal("0.083")) <= 0; // ~1 month
    }

    public boolean hasHighGamma() {
        return gamma != null && gamma.abs().compareTo(new BigDecimal("0.1")) >= 0;
    }

    public boolean isHighQuality() {
        return Boolean.TRUE.equals(isLiquid) &&
                dataQuality != null && dataQuality.compareTo(new BigDecimal("0.8")) >= 0;
    }

    public boolean hasSignificantTimeDecay() {
        return theta != null && theta.abs().compareTo(new BigDecimal("0.05")) >= 0;
    }

    public boolean isVolatilityRich() {
        return ivPercentile != null && ivPercentile.compareTo(new BigDecimal("80")) > 0;
    }

    public boolean isVolatilityCheap() {
        return ivPercentile != null && ivPercentile.compareTo(new BigDecimal("20")) < 0;
    }

    public BigDecimal getLeverage() {
        if (delta == null || lastPrice == null || lastPrice.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return delta.abs().divide(lastPrice, 2, java.math.RoundingMode.HALF_UP);
    }

    public String getRiskProfile() {
        if (hasHighGamma() && isNearExpiry()) return "HIGH_RISK";
        if (hasSignificantTimeDecay()) return "MODERATE_RISK";
        return "LOW_RISK";
    }

    public boolean needsHedging() {
        return (dollarDelta != null && dollarDelta.abs().compareTo(new BigDecimal("10000")) > 0) ||
                (dollarGamma != null && dollarGamma.abs().compareTo(new BigDecimal("5000")) > 0);
    }
}
