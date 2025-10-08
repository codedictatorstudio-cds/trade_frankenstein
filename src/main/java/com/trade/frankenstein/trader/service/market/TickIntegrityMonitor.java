package com.trade.frankenstein.trader.service.market;

import com.trade.frankenstein.trader.dto.AlertDTO;
import com.trade.frankenstein.trader.dto.InstrumentTickDTO;
import com.trade.frankenstein.trader.dto.QualityFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TickIntegrityMonitor {

    private static final Logger logger = LoggerFactory.getLogger(TickIntegrityMonitor.class);

    @Autowired
    private AlertService alertService;

    @Autowired
    private MetricsCollector metricsCollector;

    // Recent tick history for validation
    private final Map<String, Queue<InstrumentTickDTO>> recentTickHistory = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastTickTimestamps = new ConcurrentHashMap<>();

    // Configuration thresholds
    private static final int MAX_HISTORY_SIZE = 100;
    private static final BigDecimal PRICE_SPIKE_THRESHOLD = BigDecimal.valueOf(0.10); // 10%
    private static final BigDecimal VOLUME_SPIKE_THRESHOLD = BigDecimal.valueOf(5.0); // 5x normal
    private static final int MAX_STALE_SECONDS = 60;
    private static final int MAX_LATENCY_MS = 5000;
    private static final BigDecimal MIN_SPREAD_RATIO = BigDecimal.valueOf(0.0001); // 0.01%
    private static final BigDecimal MAX_SPREAD_RATIO = BigDecimal.valueOf(0.20); // 20%

    /**
     * Comprehensive tick validation
     */
    public QualityAssessment validateTick(InstrumentTickDTO tick) {
        logger.debug("Validating tick for {}: price={}, volume={}",
                tick.instrumentKey(), tick.price(), tick.volume());

        List<String> anomalies = new ArrayList<>();
        BigDecimal qualityScore = BigDecimal.ONE;
        boolean hasGaps = false;
        boolean hasSpikes = false;
        boolean isStale = false;
        boolean hasLatencyIssues = false;

        try {
            // Basic validation
            if (!isBasicTickValid(tick)) {
                anomalies.add("INVALID_BASIC_DATA");
                qualityScore = qualityScore.multiply(BigDecimal.valueOf(0.1));
            }

            // Price validation
            PriceValidationResult priceResult = validatePrice(tick);
            if (priceResult.hasIssues()) {
                anomalies.addAll(priceResult.issues());
                hasSpikes = priceResult.hasSpikes();
                qualityScore = qualityScore.multiply(priceResult.qualityMultiplier());
            }

            // Volume validation
            VolumeValidationResult volumeResult = validateVolume(tick);
            if (volumeResult.hasIssues()) {
                anomalies.addAll(volumeResult.issues());
                qualityScore = qualityScore.multiply(volumeResult.qualityMultiplier());
            }

            // Timestamp validation
            TimestampValidationResult timestampResult = validateTimestamp(tick);
            if (timestampResult.hasIssues()) {
                anomalies.addAll(timestampResult.issues());
                isStale = timestampResult.isStale();
                hasGaps = timestampResult.hasGaps();
                qualityScore = qualityScore.multiply(timestampResult.qualityMultiplier());
            }

            // Spread validation (if bid/ask available)
            if (tick.bidPrice() != null && tick.askPrice() != null) {
                SpreadValidationResult spreadResult = validateSpread(tick);
                if (spreadResult.hasIssues()) {
                    anomalies.addAll(spreadResult.issues());
                    qualityScore = qualityScore.multiply(spreadResult.qualityMultiplier());
                }
            }

            // Latency validation
            if (tick.metadata() != null && tick.metadata().containsKey("latency_ms")) {
                long latency = (Long) tick.metadata().get("latency_ms");
                if (latency > MAX_LATENCY_MS) {
                    anomalies.add("HIGH_LATENCY");
                    hasLatencyIssues = true;
                    qualityScore = qualityScore.multiply(BigDecimal.valueOf(0.8));
                }
            }

            // Update history for future validations
            updateTickHistory(tick);

            // Create quality flags
            QualityFlags qualityFlags = new QualityFlags(
                    qualityScore.setScale(4, RoundingMode.HALF_UP),
                    hasGaps, hasSpikes, isStale, hasLatencyIssues,
                    new HashSet<>(anomalies),
                    tick.metadata() != null ?
                            (Long) tick.metadata().getOrDefault("latency_ms", 0L) : 0L,
                    anomalies.isEmpty() ? "VALID" : "ISSUES_DETECTED"
            );

            // Record metrics
            metricsCollector.recordTickQuality(tick.instrumentKey(), qualityScore.doubleValue());

            // Send alerts for significant issues
            if (qualityScore.compareTo(BigDecimal.valueOf(0.5)) < 0) {
                sendQualityAlert(tick, anomalies, qualityScore);
            }

            return new QualityAssessment(qualityFlags, anomalies, qualityScore);

        } catch (Exception e) {
            logger.error("Error validating tick for {}: {}", tick.instrumentKey(), e.getMessage());
            return new QualityAssessment(
                    QualityFlags.poor("VALIDATION_ERROR"),
                    List.of("VALIDATION_ERROR"),
                    BigDecimal.valueOf(0.1)
            );
        }
    }

    /**
     * Report anomaly detected externally
     */
    public void reportAnomaly(String instrumentKey, String anomalyType, Map<String, Object> details) {
        logger.warn("Anomaly reported for {}: {} - {}", instrumentKey, anomalyType, details);

        AlertDTO alert = new AlertDTO(
                UUID.randomUUID().toString(),
                AlertDTO.AlertType.DATA_QUALITY_ISSUE,
                AlertDTO.AlertSeverity.MEDIUM,
                instrumentKey,
                "Data anomaly detected: " + anomalyType,
                LocalDateTime.now(),
                "TickIntegrityMonitor",
                details,
                false, null, null
        );

        alertService.sendAlert(alert);
        metricsCollector.recordAnomaly(instrumentKey, anomalyType);
    }

    /**
     * Get data quality statistics for an instrument
     */
    public DataQualityStats getQualityStats(String instrumentKey) {
        Queue<InstrumentTickDTO> history = recentTickHistory.get(instrumentKey);
        if (history == null || history.isEmpty()) {
            return new DataQualityStats(0, BigDecimal.ZERO, 0, LocalDateTime.now());
        }

        long totalTicks = history.size();
        double averageQuality = history.stream()
                .mapToDouble(tick -> tick.qualityFlags() != null ?
                        tick.qualityFlags().score().doubleValue() : 1.0)
                .average()
                .orElse(0.0);

        long anomalousTickCount = history.stream()
                .mapToLong(tick -> tick.qualityFlags() != null && tick.qualityFlags().hasAnomalies() ? 1 : 0)
                .sum();

        LocalDateTime lastUpdate = lastTickTimestamps.get(instrumentKey);

        return new DataQualityStats(totalTicks, BigDecimal.valueOf(averageQuality),
                anomalousTickCount, lastUpdate);
    }

    // Private validation methods

    private boolean isBasicTickValid(InstrumentTickDTO tick) {
        return tick != null &&
                tick.instrumentKey() != null && !tick.instrumentKey().trim().isEmpty() &&
                tick.price() != null && tick.price().compareTo(BigDecimal.ZERO) > 0 &&
                tick.volume() != null && tick.volume() >= 0 &&
                tick.timestamp() != null &&
                tick.source() != null && !tick.source().trim().isEmpty();
    }

    private PriceValidationResult validatePrice(InstrumentTickDTO tick) {
        List<String> issues = new ArrayList<>();
        BigDecimal qualityMultiplier = BigDecimal.ONE;
        boolean hasSpikes = false;

        Queue<InstrumentTickDTO> history = recentTickHistory.get(tick.instrumentKey());
        if (history != null && !history.isEmpty()) {
            // Calculate price statistics from recent history
            List<BigDecimal> recentPrices = history.stream()
                    .map(InstrumentTickDTO::price)
                    .toList();

            BigDecimal avgPrice = calculateAverage(recentPrices);
            BigDecimal stdDev = calculateStandardDeviation(recentPrices, avgPrice);

            // Check for price spike
            if (avgPrice != null && stdDev != null) {
                BigDecimal deviation = tick.price().subtract(avgPrice).abs();
                BigDecimal deviationRatio = deviation.divide(avgPrice, 6, RoundingMode.HALF_UP);

                if (deviationRatio.compareTo(PRICE_SPIKE_THRESHOLD) > 0) {
                    issues.add("PRICE_SPIKE");
                    hasSpikes = true;
                    qualityMultiplier = qualityMultiplier.multiply(BigDecimal.valueOf(0.5));
                }

                // Z-score check
                if (stdDev.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal zScore = deviation.divide(stdDev, 4, RoundingMode.HALF_UP);
                    if (zScore.compareTo(BigDecimal.valueOf(3.0)) > 0) {
                        issues.add("PRICE_OUTLIER");
                        qualityMultiplier = qualityMultiplier.multiply(BigDecimal.valueOf(0.7));
                    }
                }
            }
        }

        return new PriceValidationResult(issues, qualityMultiplier, hasSpikes);
    }

    private VolumeValidationResult validateVolume(InstrumentTickDTO tick) {
        List<String> issues = new ArrayList<>();
        BigDecimal qualityMultiplier = BigDecimal.ONE;

        Queue<InstrumentTickDTO> history = recentTickHistory.get(tick.instrumentKey());
        if (history != null && !history.isEmpty()) {
            // Calculate average volume
            double avgVolume = history.stream()
                    .mapToLong(InstrumentTickDTO::volume)
                    .average()
                    .orElse(0.0);

            if (avgVolume > 0) {
                double volumeRatio = tick.volume() / avgVolume;

                if (volumeRatio > VOLUME_SPIKE_THRESHOLD.doubleValue()) {
                    issues.add("VOLUME_SPIKE");
                    qualityMultiplier = qualityMultiplier.multiply(BigDecimal.valueOf(0.8));
                }

                if (tick.volume() == 0 && avgVolume > 100) {
                    issues.add("ZERO_VOLUME");
                    qualityMultiplier = qualityMultiplier.multiply(BigDecimal.valueOf(0.6));
                }
            }
        }

        return new VolumeValidationResult(issues, qualityMultiplier);
    }

    private TimestampValidationResult validateTimestamp(InstrumentTickDTO tick) {
        List<String> issues = new ArrayList<>();
        BigDecimal qualityMultiplier = BigDecimal.ONE;
        boolean isStale = false;
        boolean hasGaps = false;

        LocalDateTime now = LocalDateTime.now();

        // Check if tick is stale
        long secondsOld = ChronoUnit.SECONDS.between(tick.timestamp(), now);
        if (secondsOld > MAX_STALE_SECONDS) {
            issues.add("STALE_DATA");
            isStale = true;
            qualityMultiplier = qualityMultiplier.multiply(BigDecimal.valueOf(0.3));
        }

        // Check for future timestamps
        if (tick.timestamp().isAfter(now.plusSeconds(10))) {
            issues.add("FUTURE_TIMESTAMP");
            qualityMultiplier = qualityMultiplier.multiply(BigDecimal.valueOf(0.5));
        }

        // Check for gaps
        LocalDateTime lastTimestamp = lastTickTimestamps.get(tick.instrumentKey());
        if (lastTimestamp != null) {
            long gapSeconds = ChronoUnit.SECONDS.between(lastTimestamp, tick.timestamp());
            if (gapSeconds > 30) { // More than 30 seconds gap
                issues.add("DATA_GAP");
                hasGaps = true;
                qualityMultiplier = qualityMultiplier.multiply(BigDecimal.valueOf(0.7));
            }
        }

        return new TimestampValidationResult(issues, qualityMultiplier, isStale, hasGaps);
    }

    private SpreadValidationResult validateSpread(InstrumentTickDTO tick) {
        List<String> issues = new ArrayList<>();
        BigDecimal qualityMultiplier = BigDecimal.ONE;

        BigDecimal spread = tick.getSpread();
        BigDecimal midPrice = tick.bidPrice().add(tick.askPrice())
                .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);

        if (midPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal spreadRatio = spread.divide(midPrice, 6, RoundingMode.HALF_UP);

            if (spreadRatio.compareTo(MIN_SPREAD_RATIO) < 0) {
                issues.add("SPREAD_TOO_NARROW");
                qualityMultiplier = qualityMultiplier.multiply(BigDecimal.valueOf(0.8));
            } else if (spreadRatio.compareTo(MAX_SPREAD_RATIO) > 0) {
                issues.add("SPREAD_TOO_WIDE");
                qualityMultiplier = qualityMultiplier.multiply(BigDecimal.valueOf(0.6));
            }
        }

        // Check for inverted spread
        if (tick.bidPrice().compareTo(tick.askPrice()) >= 0) {
            issues.add("INVERTED_SPREAD");
            qualityMultiplier = qualityMultiplier.multiply(BigDecimal.valueOf(0.1));
        }

        return new SpreadValidationResult(issues, qualityMultiplier);
    }

    private void updateTickHistory(InstrumentTickDTO tick) {
        recentTickHistory.computeIfAbsent(tick.instrumentKey(), k -> new LinkedList<>())
                .offer(tick);

        Queue<InstrumentTickDTO> history = recentTickHistory.get(tick.instrumentKey());
        while (history.size() > MAX_HISTORY_SIZE) {
            history.poll();
        }

        lastTickTimestamps.put(tick.instrumentKey(), tick.timestamp());
    }

    private void sendQualityAlert(InstrumentTickDTO tick, List<String> anomalies, BigDecimal qualityScore) {
        AlertDTO alert = new AlertDTO(
                UUID.randomUUID().toString(),
                AlertDTO.AlertType.DATA_QUALITY_ISSUE,
                qualityScore.compareTo(BigDecimal.valueOf(0.2)) < 0 ?
                        AlertDTO.AlertSeverity.HIGH : AlertDTO.AlertSeverity.MEDIUM,
                tick.instrumentKey(),
                "Poor data quality detected: " + String.join(", ", anomalies),
                LocalDateTime.now(),
                "TickIntegrityMonitor",
                Map.of(
                        "quality_score", qualityScore,
                        "anomalies", anomalies,
                        "tick_price", tick.price(),
                        "tick_timestamp", tick.timestamp()
                ),
                false, null, null
        );

        alertService.sendAlert(alert);
    }

    // Utility methods
    private BigDecimal calculateAverage(List<BigDecimal> values) {
        if (values.isEmpty()) return null;

        return values.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateStandardDeviation(List<BigDecimal> values, BigDecimal mean) {
        if (values.size() < 2 || mean == null) return null;

        BigDecimal variance = values.stream()
                .map(value -> value.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size() - 1), 6, RoundingMode.HALF_UP);

        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
    }

    // Result classes
    public record QualityAssessment(
            QualityFlags qualityFlags,
            List<String> issues,
            BigDecimal overallScore
    ) {
    }

    private record PriceValidationResult(
            List<String> issues,
            BigDecimal qualityMultiplier,
            boolean hasSpikes
    ) {
        boolean hasIssues() {
            return !issues.isEmpty();
        }
    }

    private record VolumeValidationResult(
            List<String> issues,
            BigDecimal qualityMultiplier
    ) {
        boolean hasIssues() {
            return !issues.isEmpty();
        }
    }

    private record TimestampValidationResult(
            List<String> issues,
            BigDecimal qualityMultiplier,
            boolean isStale,
            boolean hasGaps
    ) {
        boolean hasIssues() {
            return !issues.isEmpty();
        }
    }

    private record SpreadValidationResult(
            List<String> issues,
            BigDecimal qualityMultiplier
    ) {
        boolean hasIssues() {
            return !issues.isEmpty();
        }
    }

    public record DataQualityStats(
            long totalTicks,
            BigDecimal averageQuality,
            long anomalousTickCount,
            LocalDateTime lastUpdate
    ) {
    }
}
