package com.trade.frankenstein.trader.service.options;

import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.common.Underlyings;
import com.trade.frankenstein.trader.dto.OptionChainData;
import com.trade.frankenstein.trader.dto.SignalDTO;
import com.trade.frankenstein.trader.dto.TradingSignal;
import com.trade.frankenstein.trader.model.documents.MarketSignalEntity;
import com.trade.frankenstein.trader.repo.documents.MarketSignalRepository;
import com.trade.frankenstein.trader.service.UpstoxService;
import com.trade.frankenstein.trader.service.market.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * PCR (Put-Call Ratio) Signal Template for generating trading signals based on option chain PCR analysis.
 * This template analyzes both Open Interest PCR and Volume PCR to generate actionable trading signals.
 */
@Component
public class PcrSignalTemplate implements SignalTemplate {

    private static final Logger logger = LoggerFactory.getLogger(PcrSignalTemplate.class);

    @Autowired
    private OptionChainService optionChainService;

    @Autowired
    private MarketDataService marketDataService;

    @Autowired
    private UpstoxService upstoxService;

    @Autowired
    private MarketSignalRepository marketSignalRepository;

    // PCR Signal Thresholds (configurable)
    private double oiPcrBullishThreshold = 0.80;    // PCR <= 0.80 suggests bullish sentiment
    private double oiPcrBearishThreshold = 1.20;    // PCR >= 1.20 suggests bearish sentiment
    private double volumePcrBullishThreshold = 0.75; // Volume PCR thresholds
    private double volumePcrBearishThreshold = 1.25;

    // Signal strength parameters
    private double minConfidenceLevel = 0.70;
    private double strongSignalMultiplier = 1.5;

    /**
     * Check if PCR signal is triggered based on current option chain data
     */
    @Override
    public boolean isTriggered(OptionChainData chainData) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            logger.debug("PCR Signal check skipped - user not logged in");
            return false;
        }

        try {
            // Get nearest expiry for analysis
            Result<LocalDate> expiryResult = getNearestExpiry();
            if (!expiryResult.isOk()) {
                logger.debug("PCR Signal check failed - no valid expiry available");
                return false;
            }

            LocalDate expiry = expiryResult.get();

            // Get current PCR values
            PcrData pcrData = getCurrentPcrData(expiry);
            if (pcrData == null) {
                logger.debug("PCR Signal check failed - no PCR data available");
                return false;
            }

            // Check if PCR values cross thresholds
            return isPcrSignalTriggered(pcrData);

        } catch (Exception e) {
            logger.error("Error checking PCR signal trigger: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Generate trading signal based on PCR analysis
     */
    @Override
    public TradingSignal generateSignal(OptionChainData chainData) {
        try {
            // Get nearest expiry
            Result<LocalDate> expiryResult = getNearestExpiry();
            if (!expiryResult.isOk()) {
                return null;
            }

            LocalDate expiry = expiryResult.get();

            // Get PCR data and market context
            PcrData pcrData = getCurrentPcrData(expiry);
            if (pcrData == null) {
                return null;
            }

            // Determine signal direction and strength
            SignalDirection direction = determineSignalDirection(pcrData);
            if (direction == SignalDirection.NEUTRAL) {
                return null;
            }

            double signalStrength = calculateSignalStrength(pcrData, direction);
            double confidence = calculateConfidence(pcrData, signalStrength);

            // Create trading signal
            TradingSignal signal = TradingSignal.builder()
                    .instrumentKey(Underlyings.NIFTY)
                    .action(mapDirectionToAction(direction))
                    .strength(signalStrength)
                    .confidence(confidence)
                    .riskAdjustedSize(calculateRiskAdjustedSize(signalStrength))
                    .entryPrice(getCurrentMarketPrice())
                    .stopLoss(calculateStopLoss(direction))
                    .takeProfit(calculateTakeProfit(direction))
                    .build();

            // Persist signal to database
            persistSignal(signal, pcrData, expiry);

            logger.info("PCR Signal generated: {} with strength {:.2f} and confidence {:.2f}",
                    direction, signalStrength, confidence);

            return signal;

        } catch (Exception e) {
            logger.error("Error generating PCR signal: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the template name
     */
    @Override
    public String getName() {
        return "PCR_SIGNAL_TEMPLATE";
    }

    /**
     * Get current threshold value (using OI PCR bullish threshold as primary)
     */
    @Override
    public double getThreshold() {
        return oiPcrBullishThreshold;
    }

    /**
     * Set threshold (updates OI PCR thresholds proportionally)
     */
    @Override
    public void setThreshold(double threshold) {
        if (threshold > 0 && threshold < 2.0) {
            this.oiPcrBullishThreshold = threshold;
            this.oiPcrBearishThreshold = 2.0 - threshold; // Symmetric around 1.0
        }
    }

    /**
     * Generate detailed SignalDTO for advanced analytics
     */
    public SignalDTO generateDetailedSignal(Object chainData) {
        try {
            Result<LocalDate> expiryResult = getNearestExpiry();
            if (!expiryResult.isOk()) {
                return null;
            }

            LocalDate expiry = expiryResult.get();
            PcrData pcrData = getCurrentPcrData(expiry);
            if (pcrData == null) {
                return null;
            }

            SignalDirection direction = determineSignalDirection(pcrData);
            if (direction == SignalDirection.NEUTRAL) {
                return null;
            }

            double signalStrength = calculateSignalStrength(pcrData, direction);
            double confidence = calculateConfidence(pcrData, signalStrength);

            // Create supporting indicators map
            Map<String, BigDecimal> supportingIndicators = new HashMap<>();
            supportingIndicators.put("oi_pcr", pcrData.oiPcr);
            supportingIndicators.put("volume_pcr", pcrData.volumePcr);
            supportingIndicators.put("oi_pcr_threshold_bullish", BigDecimal.valueOf(oiPcrBullishThreshold));
            supportingIndicators.put("oi_pcr_threshold_bearish", BigDecimal.valueOf(oiPcrBearishThreshold));
            supportingIndicators.put("volume_pcr_threshold_bullish", BigDecimal.valueOf(volumePcrBullishThreshold));
            supportingIndicators.put("volume_pcr_threshold_bearish", BigDecimal.valueOf(volumePcrBearishThreshold));

            // Create metadata
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("expiry", expiry.toString());
            metadata.put("underlying", Underlyings.NIFTY);
            metadata.put("signal_source", "PCR_ANALYSIS");
            metadata.put("market_price", getCurrentMarketPrice());

            return new SignalDTO(
                    Underlyings.NIFTY,
                    "PCR_SIGNAL",
                    BigDecimal.valueOf(pcrData.oiPcr.doubleValue()).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP),
                    LocalDateTime.now(),
                    "5M", // Timeframe
                    mapDirectionToAction(direction),
                    BigDecimal.valueOf(signalStrength).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP),
                    metadata,
                    supportingIndicators
            );

        } catch (Exception e) {
            logger.error("Error generating detailed PCR signal: {}", e.getMessage());
            return null;
        }
    }

    // Private helper methods

    private Result<LocalDate> getNearestExpiry() {
        try {
            Result<java.util.List<LocalDate>> expiriesResult =
                    optionChainService.listNearestExpiries(Underlyings.NIFTY, 1);

            if (!expiriesResult.isOk() || expiriesResult.get() == null || expiriesResult.get().isEmpty()) {
                return Result.fail("NO_EXPIRY", "No valid expiries found");
            }

            return Result.ok(expiriesResult.get().get(0));
        } catch (Exception e) {
            return Result.fail("EXPIRY_ERROR", "Error getting nearest expiry: " + e.getMessage());
        }
    }

    private PcrData getCurrentPcrData(LocalDate expiry) {
        try {
            Result<BigDecimal> oiPcrResult = optionChainService.getOiPcr(Underlyings.NIFTY, expiry);
            Result<BigDecimal> volumePcrResult = optionChainService.getVolumePcr(Underlyings.NIFTY, expiry);

            if (!oiPcrResult.isOk() || !volumePcrResult.isOk()) {
                logger.debug("PCR data not available - OI PCR: {}, Volume PCR: {}",
                        oiPcrResult.isOk(), volumePcrResult.isOk());
                return null;
            }

            return new PcrData(oiPcrResult.get(), volumePcrResult.get());
        } catch (Exception e) {
            logger.error("Error getting PCR data: {}", e.getMessage());
            return null;
        }
    }

    private boolean isPcrSignalTriggered(PcrData pcrData) {
        double oiPcr = pcrData.oiPcr.doubleValue();
        double volumePcr = pcrData.volumePcr.doubleValue();

        // Check for extreme PCR values (bullish)
        boolean oiBullish = oiPcr <= oiPcrBullishThreshold;
        boolean volumeBullish = volumePcr <= volumePcrBullishThreshold;

        // Check for extreme PCR values (bearish)
        boolean oiBearish = oiPcr >= oiPcrBearishThreshold;
        boolean volumeBearish = volumePcr >= volumePcrBearishThreshold;

        // Signal triggered if both OI and Volume PCR agree or if one is very extreme
        return (oiBullish && volumeBullish) ||
                (oiBearish && volumeBearish) ||
                (oiPcr <= (oiPcrBullishThreshold * 0.8)) ||
                (oiPcr >= (oiPcrBearishThreshold * 1.2));
    }

    private SignalDirection determineSignalDirection(PcrData pcrData) {
        double oiPcr = pcrData.oiPcr.doubleValue();
        double volumePcr = pcrData.volumePcr.doubleValue();

        int bullishSignals = 0;
        int bearishSignals = 0;

        // OI PCR signals
        if (oiPcr <= oiPcrBullishThreshold) bullishSignals++;
        if (oiPcr >= oiPcrBearishThreshold) bearishSignals++;

        // Volume PCR signals
        if (volumePcr <= volumePcrBullishThreshold) bullishSignals++;
        if (volumePcr >= volumePcrBearishThreshold) bearishSignals++;

        // Determine dominant signal
        if (bullishSignals > bearishSignals) {
            return SignalDirection.BULLISH;
        } else if (bearishSignals > bullishSignals) {
            return SignalDirection.BEARISH;
        } else {
            return SignalDirection.NEUTRAL;
        }
    }

    private double calculateSignalStrength(PcrData pcrData, SignalDirection direction) {
        double oiPcr = pcrData.oiPcr.doubleValue();
        double volumePcr = pcrData.volumePcr.doubleValue();

        double strength = 0.0;

        if (direction == SignalDirection.BULLISH) {
            // Calculate distance from bullish thresholds
            double oiDistance = Math.max(0, (oiPcrBullishThreshold - oiPcr) / oiPcrBullishThreshold);
            double volumeDistance = Math.max(0, (volumePcrBullishThreshold - volumePcr) / volumePcrBullishThreshold);
            strength = (oiDistance + volumeDistance) / 2.0;
        } else if (direction == SignalDirection.BEARISH) {
            // Calculate distance from bearish thresholds
            double oiDistance = Math.max(0, (oiPcr - oiPcrBearishThreshold) / oiPcrBearishThreshold);
            double volumeDistance = Math.max(0, (volumePcr - volumePcrBearishThreshold) / volumePcrBearishThreshold);
            strength = (oiDistance + volumeDistance) / 2.0;
        }

        // Normalize to 0-10 scale
        return Math.min(10.0, strength * 10.0);
    }

    private double calculateConfidence(PcrData pcrData, double signalStrength) {
        // Base confidence from signal strength
        double confidence = signalStrength / 10.0;

        // Boost confidence if both PCR metrics agree
        double oiPcr = pcrData.oiPcr.doubleValue();
        double volumePcr = pcrData.volumePcr.doubleValue();

        boolean bothBullish = (oiPcr <= oiPcrBullishThreshold) && (volumePcr <= volumePcrBullishThreshold);
        boolean bothBearish = (oiPcr >= oiPcrBearishThreshold) && (volumePcr >= volumePcrBearishThreshold);

        if (bothBullish || bothBearish) {
            confidence *= 1.2; // 20% boost for agreement
        }

        return Math.min(1.0, confidence);
    }

    private String mapDirectionToAction(SignalDirection direction) {
        switch (direction) {
            case BULLISH:
                return "BUY";
            case BEARISH:
                return "SELL";
            default:
                return "HOLD";
        }
    }

    private double calculateRiskAdjustedSize(double signalStrength) {
        // Risk-adjusted position size based on signal strength (0.5 to 1.5)
        return 0.5 + (signalStrength / 10.0);
    }

    private BigDecimal getCurrentMarketPrice() {
        try {
            Result<BigDecimal> priceResult = marketDataService.getLtp(Underlyings.NIFTY);
            return priceResult.isOk() ? priceResult.get() : BigDecimal.ZERO;
        } catch (Exception e) {
            logger.warn("Could not get current market price: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal calculateStopLoss(SignalDirection direction) {
        BigDecimal currentPrice = getCurrentMarketPrice();
        if (currentPrice.equals(BigDecimal.ZERO)) {
            return null;
        }

        // 2% stop loss
        BigDecimal stopLossPercent = new BigDecimal("0.02");

        if (direction == SignalDirection.BULLISH) {
            return currentPrice.multiply(BigDecimal.ONE.subtract(stopLossPercent));
        } else {
            return currentPrice.multiply(BigDecimal.ONE.add(stopLossPercent));
        }
    }

    private BigDecimal calculateTakeProfit(SignalDirection direction) {
        BigDecimal currentPrice = getCurrentMarketPrice();
        if (currentPrice.equals(BigDecimal.ZERO)) {
            return null;
        }

        // 3% take profit
        BigDecimal takeProfitPercent = new BigDecimal("0.03");

        if (direction == SignalDirection.BULLISH) {
            return currentPrice.multiply(BigDecimal.ONE.add(takeProfitPercent));
        } else {
            return currentPrice.multiply(BigDecimal.ONE.subtract(takeProfitPercent));
        }
    }

    private void persistSignal(TradingSignal signal, PcrData pcrData, LocalDate expiry) {
        try {
            MarketSignalEntity signalEntity = new MarketSignalEntity(
                    signal.getInstrumentKey(),
                    "PCR_SIGNAL",
                    BigDecimal.valueOf(pcrData.oiPcr.doubleValue()),
                    BigDecimal.valueOf(signal.getConfidence()),
                    LocalDateTime.now()
            );

            signalEntity.setDirection(signal.getAction());
            signalEntity.setStrength(BigDecimal.valueOf(signal.getStrength()));
            signalEntity.setTimeframe("5M");
            signalEntity.setSourceModel("PCR_TEMPLATE");

            // Set supporting indicators
            Map<String, BigDecimal> supportingIndicators = new HashMap<>();
            supportingIndicators.put("oi_pcr", pcrData.oiPcr);
            supportingIndicators.put("volume_pcr", pcrData.volumePcr);
            supportingIndicators.put("expiry", expiry != null ? BigDecimal.valueOf(expiry.toEpochDay()) : BigDecimal.ZERO);
            signalEntity.setSupportingIndicators(supportingIndicators);

            marketSignalRepository.save(signalEntity);
            logger.debug("PCR signal persisted to database with ID: {}", signalEntity.getId());

        } catch (Exception e) {
            logger.error("Error persisting PCR signal: {}", e.getMessage());
        }
    }

    // Inner classes and enums

    private enum SignalDirection {
        BULLISH, BEARISH, NEUTRAL
    }

    private static class PcrData {
        final BigDecimal oiPcr;
        final BigDecimal volumePcr;

        PcrData(BigDecimal oiPcr, BigDecimal volumePcr) {
            this.oiPcr = oiPcr;
            this.volumePcr = volumePcr;
        }
    }

    // Configuration methods (for external configuration)

    public void setOiPcrThresholds(double bullishThreshold, double bearishThreshold) {
        this.oiPcrBullishThreshold = bullishThreshold;
        this.oiPcrBearishThreshold = bearishThreshold;
    }

    public void setVolumePcrThresholds(double bullishThreshold, double bearishThreshold) {
        this.volumePcrBullishThreshold = bullishThreshold;
        this.volumePcrBearishThreshold = bearishThreshold;
    }

    public void setMinConfidenceLevel(double minConfidenceLevel) {
        this.minConfidenceLevel = minConfidenceLevel;
    }

    public Map<String, Object> getConfiguration() {
        Map<String, Object> config = new HashMap<>();
        config.put("oi_pcr_bullish_threshold", oiPcrBullishThreshold);
        config.put("oi_pcr_bearish_threshold", oiPcrBearishThreshold);
        config.put("volume_pcr_bullish_threshold", volumePcrBullishThreshold);
        config.put("volume_pcr_bearish_threshold", volumePcrBearishThreshold);
        config.put("min_confidence_level", minConfidenceLevel);
        config.put("strong_signal_multiplier", strongSignalMultiplier);
        return config;
    }
}
