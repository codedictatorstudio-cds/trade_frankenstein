package com.trade.frankenstein.trader.service.sentiment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@Slf4j
public class PositionSizer {

    @Value("${trading.risk.max-position-pct:2.0}")
    private double maxPositionPct;

    @Value("${trading.risk.base-position-pct:1.0}")
    private double basePositionPct;

    @Value("${trading.risk.volatility-threshold:3.0}")
    private double volatilityThreshold;

    @Value("${trading.risk.sentiment-threshold:60.0}")
    private double sentimentThreshold;

    /**
     * Calculate optimal position size based on sentiment and market conditions, portfolio denominated in INR.
     *
     * @param sentimentScore 0..100 (50 = neutral)
     * @param volatilityPct  daily volatility as percent (e.g., 2.5 = 2.5%)
     * @param maxRiskPct     max portfolio risk per trade (e.g., 1.0 = 1%)
     * @return position size as fraction of portfolio (0.0 to maxRiskPct)
     */
    public BigDecimal size(BigDecimal sentimentScore,
                           BigDecimal volatilityPct,
                           BigDecimal maxRiskPct) {
        try {
            if (sentimentScore == null || volatilityPct == null || maxRiskPct == null) {
                log.warn("Null parameters in INR position sizing, using minimum size");
                return maxRiskPct.multiply(BigDecimal.valueOf(0.1)); // 10% of max risk
            }

            // Base position size
            BigDecimal baseSize = maxRiskPct.multiply(BigDecimal.valueOf(basePositionPct));

            // Sentiment adjustment factor (0.5 to 2.0)
            BigDecimal sentimentFactor = calculateSentimentFactor(sentimentScore);

            // Volatility adjustment factor (0.3 to 1.5)
            BigDecimal volatilityFactor = calculateVolatilityFactor(volatilityPct);

            // Calculate adjusted position size
            BigDecimal adjustedSize = baseSize
                    .multiply(sentimentFactor)
                    .multiply(volatilityFactor);

            // Apply caps and floors
            BigDecimal minSize = maxRiskPct.multiply(BigDecimal.valueOf(0.1)); // 10% minimum
            BigDecimal cappedSize = adjustedSize
                    .max(minSize)
                    .min(maxRiskPct);

            log.debug("INR Position sizing: sentiment={}, volatility={}%, sentimentFactor={}, volFactor={}, finalSize={}%",
                    sentimentScore, volatilityPct, sentimentFactor,
                    volatilityFactor, cappedSize.multiply(BigDecimal.valueOf(100)));

            return cappedSize.setScale(4, RoundingMode.HALF_UP);

        } catch (Exception e) {
            log.error("Error calculating INR position size: {}", e.getMessage());
            return maxRiskPct.multiply(BigDecimal.valueOf(0.1)); // Conservative fallback
        }
    }

    /**
     * Kelly Criterion-based position sizing, portfolio denominated in INR.
     *
     * @param winProbability probability of winning trade (0.0 to 1.0)
     * @param avgWin         average win amount (INR)
     * @param avgLoss        average loss amount (INR)
     * @param maxRiskPct     maximum risk per trade
     * @return optimal position size using Kelly formula
     */
    public BigDecimal kellySize(BigDecimal winProbability,
                                BigDecimal avgWin,
                                BigDecimal avgLoss,
                                BigDecimal maxRiskPct) {
        try {
            if (winProbability == null || avgWin == null || avgLoss == null) {
                return maxRiskPct.multiply(BigDecimal.valueOf(0.1));
            }

            BigDecimal b = avgWin.divide(avgLoss, 4, RoundingMode.HALF_UP);
            BigDecimal p = winProbability;
            BigDecimal q = BigDecimal.ONE.subtract(p);

            BigDecimal kellyPct = b.multiply(p).subtract(q).divide(b, 4, RoundingMode.HALF_UP);
            BigDecimal cappedKelly = kellyPct.min(maxRiskPct.multiply(BigDecimal.valueOf(0.25)));

            return cappedKelly.max(maxRiskPct.multiply(BigDecimal.valueOf(0.01)));

        } catch (Exception e) {
            log.error("Error calculating Kelly position size (INR): {}", e.getMessage());
            return maxRiskPct.multiply(BigDecimal.valueOf(0.1));
        }
    }

    /**
     * Risk-parity based position sizing (INR portfolio).
     *
     * @param expectedReturn expected return of the asset
     * @param volatility     volatility of the asset
     * @param riskBudget     total risk budget
     * @return position size for equal risk contribution
     */
    public BigDecimal riskParitySize(BigDecimal expectedReturn,
                                     BigDecimal volatility,
                                     BigDecimal riskBudget) {
        try {
            if (volatility == null || volatility.compareTo(BigDecimal.ZERO) <= 0) {
                return riskBudget.multiply(BigDecimal.valueOf(0.1));
            }

            BigDecimal size = riskBudget.divide(volatility, 4, RoundingMode.HALF_UP);
            return size.max(riskBudget.multiply(BigDecimal.valueOf(0.01)))
                    .min(riskBudget);

        } catch (Exception e) {
            log.error("Error calculating risk parity size (INR): {}", e.getMessage());
            return riskBudget.multiply(BigDecimal.valueOf(0.1));
        }
    }

    /**
     * ATR (Average True Range) based position sizing (INR).
     *
     * @param atr        current ATR value
     * @param riskAmount INR amount willing to risk
     * @param price      current price of the asset (INR)
     * @return number of shares/units to trade
     */
    public BigDecimal atrBasedSize(BigDecimal atr,
                                   BigDecimal riskAmount,
                                   BigDecimal price) {
        try {
            if (atr == null || atr.compareTo(BigDecimal.ZERO) <= 0 || price == null) {
                return BigDecimal.ZERO;
            }
            BigDecimal atrMultiplier = BigDecimal.valueOf(2.0); // 2x ATR stop loss
            BigDecimal stopDistance = atr.multiply(atrMultiplier);
            return riskAmount.divide(stopDistance, 0, RoundingMode.DOWN);
        } catch (Exception e) {
            log.error("Error calculating ATR-based position size (INR): {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    // --- Helper Methods ---

    private BigDecimal calculateSentimentFactor(BigDecimal sentimentScore) {
        double score = sentimentScore.doubleValue();

        if (score >= 80) {
            return BigDecimal.valueOf(2.0); // Very bullish - double position
        } else if (score >= 70) {
            return BigDecimal.valueOf(1.5);
        } else if (score >= 60) {
            return BigDecimal.valueOf(1.2);
        } else if (score >= 40) {
            return BigDecimal.valueOf(1.0);
        } else if (score >= 30) {
            return BigDecimal.valueOf(0.8);
        } else if (score >= 20) {
            return BigDecimal.valueOf(0.6);
        } else {
            return BigDecimal.valueOf(0.5);
        }
    }

    private BigDecimal calculateVolatilityFactor(BigDecimal volatilityPct) {
        double vol = volatilityPct.doubleValue();

        if (vol <= 1.0) {
            return BigDecimal.valueOf(1.5);
        } else if (vol <= 2.0) {
            return BigDecimal.valueOf(1.2);
        } else if (vol <= 3.0) {
            return BigDecimal.valueOf(1.0);
        } else if (vol <= 5.0) {
            return BigDecimal.valueOf(0.8);
        } else if (vol <= 8.0) {
            return BigDecimal.valueOf(0.6);
        } else {
            return BigDecimal.valueOf(0.3);
        }
    }

    public PositionSizeRecommendation getRecommendation(BigDecimal sentimentScore,
                                                        BigDecimal volatilityPct,
                                                        BigDecimal maxRiskPct,
                                                        BigDecimal portfolioValueINR) {
        BigDecimal positionSize = size(sentimentScore, volatilityPct, maxRiskPct);
        BigDecimal rupeeAmount = portfolioValueINR.multiply(positionSize);

        return PositionSizeRecommendation.builder()
                .positionSizePercent(positionSize.multiply(BigDecimal.valueOf(100)))
                .rupeeAmount(rupeeAmount)
                .sentimentScore(sentimentScore)
                .volatilityPercent(volatilityPct)
                .sentimentFactor(calculateSentimentFactor(sentimentScore))
                .volatilityFactor(calculateVolatilityFactor(volatilityPct))
                .riskLevel(determineRiskLevel(positionSize, maxRiskPct))
                .build();
    }

    private String determineRiskLevel(BigDecimal positionSize, BigDecimal maxRisk) {
        double ratio = positionSize.divide(maxRisk, 4, RoundingMode.HALF_UP).doubleValue();

        if (ratio >= 0.8) return "HIGH";
        else if (ratio >= 0.5) return "MEDIUM";
        else if (ratio >= 0.2) return "LOW";
        else return "CONSERVATIVE";
    }

    // --- Inner Class for Detailed Recommendations in INR ---

    public static class PositionSizeRecommendation {
        private BigDecimal positionSizePercent;
        private BigDecimal rupeeAmount;
        private BigDecimal sentimentScore;
        private BigDecimal volatilityPercent;
        private BigDecimal sentimentFactor;
        private BigDecimal volatilityFactor;
        private String riskLevel;

        // Builder pattern
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private final PositionSizeRecommendation rec = new PositionSizeRecommendation();

            public Builder positionSizePercent(BigDecimal val) {
                rec.positionSizePercent = val;
                return this;
            }

            public Builder rupeeAmount(BigDecimal val) {
                rec.rupeeAmount = val;
                return this;
            }

            public Builder sentimentScore(BigDecimal val) {
                rec.sentimentScore = val;
                return this;
            }

            public Builder volatilityPercent(BigDecimal val) {
                rec.volatilityPercent = val;
                return this;
            }

            public Builder sentimentFactor(BigDecimal val) {
                rec.sentimentFactor = val;
                return this;
            }

            public Builder volatilityFactor(BigDecimal val) {
                rec.volatilityFactor = val;
                return this;
            }

            public Builder riskLevel(String val) {
                rec.riskLevel = val;
                return this;
            }

            public PositionSizeRecommendation build() {
                return rec;
            }
        }

        // Getters
        public BigDecimal getPositionSizePercent() {
            return positionSizePercent;
        }

        public BigDecimal getRupeeAmount() {
            return rupeeAmount;
        }

        public BigDecimal getSentimentScore() {
            return sentimentScore;
        }

        public BigDecimal getVolatilityPercent() {
            return volatilityPercent;
        }

        public BigDecimal getSentimentFactor() {
            return sentimentFactor;
        }

        public BigDecimal getVolatilityFactor() {
            return volatilityFactor;
        }

        public String getRiskLevel() {
            return riskLevel;
        }
    }
}
