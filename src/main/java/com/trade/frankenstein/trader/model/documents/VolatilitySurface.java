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
import java.util.TreeMap;

@Document("volatility_surface")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VolatilitySurface {
    @Id
    private String id;

    @Indexed
    private String underlyingKey;

    @Indexed
    private LocalDate expiry;

    @Indexed
    private Instant timestamp;

    // Volatility Surface Data: Moneyness -> IV
    private Map<BigDecimal, BigDecimal> callVolatilities;
    private Map<BigDecimal, BigDecimal> putVolatilities;

    // Surface Characteristics
    private BigDecimal atmVolatility;
    private BigDecimal skew25Delta;
    private BigDecimal skew10Delta;
    private BigDecimal convexity;
    private BigDecimal termStructure;

    // Risk Metrics
    private BigDecimal volOfVol;
    private BigDecimal volRisk;
    private BigDecimal correlationDecay;

    // Surface Quality Metrics
    private BigDecimal interpolationError;
    private BigDecimal arbitrageFreeScore;
    private Integer dataPoints;
    private BigDecimal smoothnessScore;

    // Time Decay Analysis
    private BigDecimal thetaDecay;
    private BigDecimal timeValueDecay;
    private BigDecimal accelerationFactor;

    // Strike Distribution Analysis
    private BigDecimal strikeSpread;
    private BigDecimal liquidityConcentration;
    private Map<BigDecimal, BigDecimal> strikeDensity;

    // Model Parameters
    private BigDecimal hullWhiteAlpha;
    private BigDecimal hullWhiteSigma;
    private BigDecimal sabr_alpha;
    private BigDecimal sabr_beta;
    private BigDecimal sabr_rho;
    private BigDecimal sabr_nu;

    // Calibration Quality
    private BigDecimal calibrationError;
    private BigDecimal modelFit;
    private Instant calibrationTime;

    // Market Context
    private BigDecimal underlyingPrice;
    private BigDecimal interestRate;
    private BigDecimal dividendYield;
    private Integer daysToExpiry;

    // Surface Derivatives
    private Map<BigDecimal, BigDecimal> vegaProfile;
    private Map<BigDecimal, BigDecimal> volGamma;
    private Map<BigDecimal, BigDecimal> volVanna;

    public BigDecimal getImpliedVolatility(BigDecimal moneyness, boolean isCall) {
        Map<BigDecimal, BigDecimal> vols = isCall ? callVolatilities : putVolatilities;
        if (vols == null || vols.isEmpty()) {
            return atmVolatility;
        }

        // Find closest moneyness levels for interpolation
        TreeMap<BigDecimal, BigDecimal> sortedVols = new TreeMap<>(vols);

        Map.Entry<BigDecimal, BigDecimal> lower = sortedVols.floorEntry(moneyness);
        Map.Entry<BigDecimal, BigDecimal> higher = sortedVols.ceilingEntry(moneyness);

        if (lower == null) return higher != null ? higher.getValue() : atmVolatility;
        if (higher == null) return lower.getValue();
        if (lower.getKey().equals(higher.getKey())) return lower.getValue();

        // Linear interpolation
        BigDecimal weight = moneyness.subtract(lower.getKey())
                .divide(higher.getKey().subtract(lower.getKey()), 4, java.math.RoundingMode.HALF_UP);

        return lower.getValue().add(
                higher.getValue().subtract(lower.getValue()).multiply(weight)
        );
    }

    public boolean isArbitrageFree() {
        return arbitrageFreeScore != null &&
                arbitrageFreeScore.compareTo(new BigDecimal("0.95")) >= 0;
    }

    public boolean hasGoodCalibration() {
        return calibrationError != null &&
                calibrationError.compareTo(new BigDecimal("0.05")) <= 0;
    }
}
