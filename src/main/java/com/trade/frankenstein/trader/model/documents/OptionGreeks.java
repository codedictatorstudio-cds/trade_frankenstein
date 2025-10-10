package com.trade.frankenstein.trader.model.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Enhanced OptionGreeks model with second-order Greeks for advanced validation
 */
@Document(collection = "option_greeks")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OptionGreeks {

    @Id
    private String id;

    // First-order Greeks
    private BigDecimal delta;      // Price sensitivity to underlying movement
    private BigDecimal gamma;      // Delta sensitivity to underlying movement
    private BigDecimal theta;      // Time decay (daily)
    private BigDecimal vega;       // Volatility sensitivity (per 1%)
    private BigDecimal rho;        // Interest rate sensitivity (per 1%)

    // Second-order Greeks for advanced validation
    private BigDecimal charm;      // Delta decay over time
    private BigDecimal vanna;      // Vega-delta relationship
    private BigDecimal vomma;      // Vega convexity
    private BigDecimal ultima;     // Vomma sensitivity to volatility

    // Calculation metadata
    private LocalDateTime calculationTime;
    private String calculationMethod;  // "NEWTON_RAPHSON", "BRENT", "INTERPOLATED"
    private Double calculationAccuracy; // Error tolerance achieved
    private String modelUsed;      // "BLACK_SCHOLES", "BINOMIAL", "MONTE_CARLO"

    // Validation flags
    private Boolean isValidated;
    private List<String> validationWarnings;

    /**
     * Check if Greeks are stale and need recalculation
     */
    public boolean isStale(int maxAgeMinutes) {
        if (calculationTime == null) return true;
        return calculationTime.isBefore(LocalDateTime.now().minusMinutes(maxAgeMinutes));
    }

    /**
     * Get Greeks as a single risk vector for portfolio calculations
     */
    public double[] getRiskVector() {
        return new double[]{
                delta != null ? delta.doubleValue() : 0.0,
                gamma != null ? gamma.doubleValue() : 0.0,
                theta != null ? theta.doubleValue() : 0.0,
                vega != null ? vega.doubleValue() : 0.0,
                rho != null ? rho.doubleValue() : 0.0
        };
    }
}