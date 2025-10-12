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

@Document("greeks_snapshot")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GreeksSnapshot {
    @Id
    private String id;

    @Indexed
    private String underlyingKey;

    @Indexed
    private LocalDate expiry;

    @Indexed
    private String instrumentKey;

    @Indexed
    private Instant timestamp;

    // Basic Greeks
    private BigDecimal delta;
    private BigDecimal gamma;
    private BigDecimal theta;
    private BigDecimal vega;
    private BigDecimal rho;
    private BigDecimal impliedVolatility;

    // Additional calculated metrics
    private BigDecimal charm; // Delta decay (dDelta/dTime)
    private BigDecimal vomma; // Vega convexity (dVega/dVol)
    private BigDecimal vanna; // Delta-Vega cross derivative (dDelta/dVol)
    private BigDecimal speed; // Gamma decay (dGamma/dSpot)
    private BigDecimal zomma; // Gamma-Vega cross (dGamma/dVol)
    private BigDecimal color; // Gamma decay in time (dGamma/dTime)

    // Market Data Context
    private BigDecimal underlyingPrice;
    private BigDecimal optionPrice;
    private BigDecimal bidPrice;
    private BigDecimal askPrice;
    private BigDecimal midPrice;
    private Long volume;
    private Long openInterest;

    // Derived Metrics
    private BigDecimal moneyness; // S/K for calls, K/S for puts
    private BigDecimal timeToExpiry; // in years
    private BigDecimal intrinsicValue;
    private BigDecimal timeValue;
    private BigDecimal elasticity; // % change in option price for 1% change in underlying

    // Risk Metrics
    private BigDecimal effectiveDelta; // Delta adjusted for gamma
    private BigDecimal dollarDelta; // Delta * underlying price * contract multiplier
    private BigDecimal dollarGamma; // Gamma * underlying price^2 * contract multiplier
    private BigDecimal dollarTheta; // Theta * contract multiplier
    private BigDecimal dollarVega; // Vega * contract multiplier / 100

    // Quality Indicators
    private BigDecimal bidAskSpread;
    private BigDecimal spreadPercentage;
    private Boolean isLiquid;
    private BigDecimal dataQuality; // 0-1 score

    // Model Information
    private String pricingModel; // BLACK_SCHOLES, BINOMIAL, MONTE_CARLO
    private BigDecimal modelError; // Difference between model and market price
    private BigDecimal impliedVolError; // IV calculation error

    // Historical Context
    private BigDecimal deltaChange; // Change from previous snapshot
    private BigDecimal gammaChange;
    private BigDecimal thetaChange;
    private BigDecimal vegaChange;
    private BigDecimal ivChange;

    // Convenience methods
    public boolean isInTheMoney() {
        return intrinsicValue != null && intrinsicValue.compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isNearExpiry() {
        return timeToExpiry != null && timeToExpiry.compareTo(new BigDecimal("0.083")) <= 0; // 1 month
    }

    public boolean hasHighGamma() {
        return gamma != null && gamma.compareTo(new BigDecimal("0.1")) >= 0;
    }

    public boolean isHighQuality() {
        return dataQuality != null && dataQuality.compareTo(new BigDecimal("0.8")) >= 0 &&
                Boolean.TRUE.equals(isLiquid);
    }

    public BigDecimal getEffectiveSpread() {
        if (bidPrice == null || askPrice == null || midPrice == null) return null;
        if (midPrice.compareTo(BigDecimal.ZERO) == 0) return null;

        return bidAskSpread.divide(midPrice, 4, java.math.RoundingMode.HALF_UP);
    }

    public BigDecimal getLeverage() {
        if (delta == null || underlyingPrice == null || optionPrice == null) return null;
        if (optionPrice.compareTo(BigDecimal.ZERO) == 0) return null;

        return delta.multiply(underlyingPrice).divide(optionPrice, 2, java.math.RoundingMode.HALF_UP);
    }
}
