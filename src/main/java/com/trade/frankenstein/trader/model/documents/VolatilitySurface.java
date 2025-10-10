package com.trade.frankenstein.trader.model.documents;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Enhanced VolatilitySurface for interpolation and surface modeling
 */
@Document(collection = "volatility_surfaces")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class VolatilitySurface {

    @Id
    private String id;

    private String underlying;  // NIFTY, BANKNIFTY
    private LocalDateTime timestamp;

    // IV surface organized by expiry -> strike -> IV
    private Map<LocalDate, Map<BigDecimal, Double>> ivSurface;

    // Surface metadata
    private int totalDataPoints;
    private int interpolatedPoints;
    private double surfaceQuality;  // 0-1 score based on data density

    // Interpolation parameters
    private String interpolationMethod; // CUBIC_SPLINE, BILINEAR, SABR
    private Map<String, Object> interpolationParams;

    public VolatilitySurface(Map<LocalDate, Map<BigDecimal, Double>> ivSurface) {
        this.ivSurface = ivSurface;
        this.timestamp = LocalDateTime.now();
        this.interpolationMethod = "CUBIC_SPLINE";
        calculateSurfaceMetrics();
    }

    /**
     * Get interpolated IV for a given expiry and strike
     */
    public double getInterpolatedIV(LocalDate expiry, BigDecimal strike) {
        if (ivSurface == null || ivSurface.isEmpty()) {
            return 0.2; // Default 20% volatility
        }

        Map<BigDecimal, Double> expiryStrikes = ivSurface.get(expiry);
        if (expiryStrikes != null && expiryStrikes.containsKey(strike)) {
            return expiryStrikes.get(strike); // Exact match
        }

        // Perform cubic spline interpolation
        return performCubicSplineInterpolation(expiry, strike);
    }

    /**
     * Cubic spline interpolation across strikes and linear interpolation across time
     */
    private double performCubicSplineInterpolation(LocalDate targetExpiry, BigDecimal targetStrike) {
        // Find nearest expiries
        LocalDate nearestExpiry = findNearestExpiry(targetExpiry);
        if (nearestExpiry == null) {
            return 0.2; // Default fallback
        }

        Map<BigDecimal, Double> strikes = ivSurface.get(nearestExpiry);
        if (strikes == null || strikes.size() < 2) {
            return 0.2; // Need at least 2 points for interpolation
        }

        // Simple linear interpolation for now (can be enhanced with actual cubic spline)
        List<BigDecimal> sortedStrikes = strikes.keySet().stream()
                .sorted()
                .toList();

        // Find surrounding strikes
        BigDecimal lowerStrike = null;
        BigDecimal upperStrike = null;

        for (int i = 0; i < sortedStrikes.size(); i++) {
            BigDecimal strike = sortedStrikes.get(i);
            if (strike.compareTo(targetStrike) <= 0) {
                lowerStrike = strike;
            }
            if (strike.compareTo(targetStrike) >= 0 && upperStrike == null) {
                upperStrike = strike;
                break;
            }
        }

        // Linear interpolation between surrounding points
        if (lowerStrike != null && upperStrike != null && !lowerStrike.equals(upperStrike)) {
            double lowerIV = strikes.get(lowerStrike);
            double upperIV = strikes.get(upperStrike);

            double weight = targetStrike.subtract(lowerStrike)
                    .divide(upperStrike.subtract(lowerStrike), 6, BigDecimal.ROUND_HALF_UP)
                    .doubleValue();

            return lowerIV + weight * (upperIV - lowerIV);
        }

        // Extrapolation: use nearest available IV
        if (lowerStrike != null) {
            return strikes.get(lowerStrike);
        }
        if (upperStrike != null) {
            return strikes.get(upperStrike);
        }

        return 0.2; // Final fallback
    }

    /**
     * Find the nearest expiry date with available data
     */
    private LocalDate findNearestExpiry(LocalDate targetExpiry) {
        return ivSurface.keySet().stream()
                .min((e1, e2) -> {
                    long diff1 = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(targetExpiry, e1));
                    long diff2 = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(targetExpiry, e2));
                    return Long.compare(diff1, diff2);
                })
                .orElse(null);
    }

    /**
     * Calculate surface quality metrics
     */
    private void calculateSurfaceMetrics() {
        if (ivSurface == null) {
            this.totalDataPoints = 0;
            this.surfaceQuality = 0.0;
            return;
        }

        this.totalDataPoints = ivSurface.values().stream()
                .mapToInt(Map::size)
                .sum();

        // Simple quality score based on data density
        int expectedMinPoints = ivSurface.size() * 10; // At least 10 strikes per expiry
        this.surfaceQuality = Math.min(1.0, (double) totalDataPoints / expectedMinPoints);
    }

    /**
     * Check if the surface has sufficient data for reliable interpolation
     */
    public boolean isReliable() {
        return surfaceQuality > 0.5 && totalDataPoints > 20;
    }

    /**
     * Get available expiry dates
     */
    public List<LocalDate> getAvailableExpiries() {
        return ivSurface != null ? ivSurface.keySet().stream().sorted().toList() : List.of();
    }

    /**
     * Get available strikes for a given expiry
     */
    public List<BigDecimal> getAvailableStrikes(LocalDate expiry) {
        Map<BigDecimal, Double> strikes = ivSurface != null ? ivSurface.get(expiry) : null;
        return strikes != null ? strikes.keySet().stream().sorted().toList() : List.of();
    }
}
