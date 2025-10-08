package com.trade.frankenstein.trader.service.market;

import com.trade.frankenstein.trader.model.documents.MetricsEntity;
import com.trade.frankenstein.trader.repo.documents.MetricsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

@Service
public class MetricsCollector {

    private static final Logger logger = LoggerFactory.getLogger(MetricsCollector.class);

    @Autowired
    private MetricsRepository metricsRepository;

    // In-memory metrics for real-time tracking
    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, List<Long>> latencyHistograms = new ConcurrentHashMap<>();
    private final Map<String, Double> gauges = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> timestamps = new ConcurrentHashMap<>();

    // Performance tracking
    private final Map<String, PerformanceTracker> performanceTrackers = new ConcurrentHashMap<>();

    // Configuration
    private static final int MAX_HISTOGRAM_SIZE = 1000;
    private static final int METRICS_RETENTION_MINUTES = 60;

    /**
     * Record source latency
     */
    public void recordSourceLatency(String source, long latencyMs) {
        String metricKey = "source.latency." + source;

        // Update counter
        incrementCounter("source.requests." + source);

        // Add to histogram
        latencyHistograms.computeIfAbsent(metricKey, k -> new ArrayList<>()).add(latencyMs);

        // Trim histogram if too large
        List<Long> histogram = latencyHistograms.get(metricKey);
        if (histogram.size() > MAX_HISTOGRAM_SIZE) {
            histogram.subList(0, histogram.size() - MAX_HISTOGRAM_SIZE).clear();
        }

        // Update gauge with average latency
        updateGauge(metricKey + ".avg", calculateAverageLatency(histogram));

        // Track timestamp
        timestamps.put(metricKey, LocalDateTime.now());

        logger.debug("Recorded source latency: {} = {}ms", source, latencyMs);
    }

    /**
     * Record source failure
     */
    public void recordSourceFailure(String source, String errorType) {
        incrementCounter("source.failures." + source);
        incrementCounter("source.failures.by_type." + errorType);

        // Update failure rate gauge
        updateSourceFailureRate(source);

        logger.debug("Recorded source failure: {} - {}", source, errorType);
    }

    /**
     * Record tick quality metrics
     */
    public void recordTickQuality(String instrumentKey, double qualityScore) {
        String metricKey = "tick.quality." + instrumentKey;

        incrementCounter("tick.processed." + instrumentKey);
        updateGauge(metricKey, qualityScore);

        // Track quality over time
        PerformanceTracker tracker = performanceTrackers.computeIfAbsent(
                metricKey, k -> new PerformanceTracker());
        tracker.addDataPoint(qualityScore);

        timestamps.put(metricKey, LocalDateTime.now());

        logger.debug("Recorded tick quality: {} = {}", instrumentKey, qualityScore);
    }

    /**
     * Record anomaly detection
     */
    public void recordAnomaly(String instrumentKey, String anomalyType) {
        incrementCounter("anomalies.total");
        incrementCounter("anomalies.by_instrument." + instrumentKey);
        incrementCounter("anomalies.by_type." + anomalyType);

        logger.debug("Recorded anomaly: {} - {}", instrumentKey, anomalyType);
    }

    /**
     * Record signal generation metrics
     */
    public void recordSignalGenerated(String instrumentKey, String signalType, double confidence) {
        incrementCounter("signals.generated.total");
        incrementCounter("signals.generated.by_instrument." + instrumentKey);
        incrementCounter("signals.generated.by_type." + signalType);

        updateGauge("signals.confidence." + signalType, confidence);

        // Track high confidence signals separately
        if (confidence >= 0.8) {
            incrementCounter("signals.high_confidence." + signalType);
        }

        logger.debug("Recorded signal: {} {} confidence={}", instrumentKey, signalType, confidence);
    }

    /**
     * Record trade execution metrics
     */
    public void recordTradeExecution(String instrumentKey, String action, BigDecimal amount, long executionTimeMs) {
        incrementCounter("trades.executed.total");
        incrementCounter("trades.executed.by_instrument." + instrumentKey);
        incrementCounter("trades.executed.by_action." + action);

        // Record execution time
        recordLatency("trade.execution.time", executionTimeMs);

        // Record trade size
        updateGauge("trades.size.avg." + instrumentKey, amount.doubleValue());

        logger.debug("Recorded trade execution: {} {} {} in {}ms",
                instrumentKey, action, amount, executionTimeMs);
    }

    /**
     * Record system health metrics
     */
    public void recordSystemHealth(String component, double healthScore) {
        updateGauge("system.health." + component, healthScore);
        timestamps.put("system.health." + component, LocalDateTime.now());

        logger.debug("Recorded system health: {} = {}", component, healthScore);
    }

    /**
     * Record API call metrics
     */
    public void recordApiCall(String endpoint, boolean success, long responseTimeMs) {
        incrementCounter("api.calls.total." + endpoint);

        if (success) {
            incrementCounter("api.calls.success." + endpoint);
        } else {
            incrementCounter("api.calls.failure." + endpoint);
        }

        recordLatency("api.response_time." + endpoint, responseTimeMs);

        // Update success rate gauge
        updateApiSuccessRate(endpoint);

        logger.debug("Recorded API call: {} success={} time={}ms", endpoint, success, responseTimeMs);
    }

    /**
     * Record memory and CPU usage
     */
    public void recordSystemResources() {
        Runtime runtime = Runtime.getRuntime();

        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        updateGauge("system.memory.used_mb", usedMemory / (1024.0 * 1024.0));
        updateGauge("system.memory.free_mb", freeMemory / (1024.0 * 1024.0));
        updateGauge("system.memory.total_mb", totalMemory / (1024.0 * 1024.0));
        updateGauge("system.memory.max_mb", maxMemory / (1024.0 * 1024.0));
        updateGauge("system.memory.usage_percent", (usedMemory * 100.0) / maxMemory);

        logger.debug("Recorded system resources: memory usage = {}%", (usedMemory * 100.0) / maxMemory);
    }

    /**
     * Get current counter value
     */
    public long getCounter(String metricName) {
        LongAdder counter = counters.get(metricName);
        return counter != null ? counter.sum() : 0L;
    }

    /**
     * Get current gauge value
     */
    public Double getGauge(String metricName) {
        return gauges.get(metricName);
    }

    /**
     * Get latency statistics for a metric
     */
    public LatencyStatistics getLatencyStatistics(String metricName) {
        List<Long> histogram = latencyHistograms.get(metricName);
        if (histogram == null || histogram.isEmpty()) {
            return null;
        }

        List<Long> sortedLatencies = histogram.stream().sorted().collect(Collectors.toList());

        long min = sortedLatencies.get(0);
        long max = sortedLatencies.get(sortedLatencies.size() - 1);
        long p50 = sortedLatencies.get(sortedLatencies.size() / 2);
        long p95 = sortedLatencies.get((int) (sortedLatencies.size() * 0.95));
        long p99 = sortedLatencies.get((int) (sortedLatencies.size() * 0.99));
        double avg = sortedLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0);

        return new LatencyStatistics(min, max, (long) avg, p50, p95, p99, sortedLatencies.size());
    }

    /**
     * Get performance tracker for a metric
     */
    public PerformanceStatistics getPerformanceStatistics(String metricName) {
        PerformanceTracker tracker = performanceTrackers.get(metricName);
        return tracker != null ? tracker.getStatistics() : null;
    }

    /**
     * Get all metrics summary
     */
    public MetricsSummary getMetricsSummary() {
        Map<String, Long> counterSnapshot = new HashMap<>();
        counters.forEach((key, value) -> counterSnapshot.put(key, value.sum()));

        Map<String, Double> gaugeSnapshot = new HashMap<>(gauges);

        Map<String, LatencyStatistics> latencySnapshot = new HashMap<>();
        latencyHistograms.keySet().forEach(key -> {
            LatencyStatistics stats = getLatencyStatistics(key);
            if (stats != null) {
                latencySnapshot.put(key, stats);
            }
        });

        return new MetricsSummary(counterSnapshot, gaugeSnapshot, latencySnapshot, LocalDateTime.now());
    }

    /**
     * Reset all metrics
     */
    public void resetMetrics() {
        counters.clear();
        latencyHistograms.clear();
        gauges.clear();
        performanceTrackers.clear();
        timestamps.clear();

        logger.info("All metrics have been reset");
    }

    // Scheduled methods

    /**
     * Periodically persist metrics to database
     */
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void persistMetrics() {
        try {
            LocalDateTime now = LocalDateTime.now();

            // Persist counters
            counters.forEach((key, value) -> {
                MetricsEntity entity = new MetricsEntity();
                entity.setMetricName(key);
                entity.setMetricType("COUNTER");
                entity.setValue(BigDecimal.valueOf(value.sum()));
                entity.setTimestamp(now);
                metricsRepository.save(entity);
            });

            // Persist gauges
            gauges.forEach((key, value) -> {
                MetricsEntity entity = new MetricsEntity();
                entity.setMetricName(key);
                entity.setMetricType("GAUGE");
                entity.setValue(BigDecimal.valueOf(value));
                entity.setTimestamp(now);
                metricsRepository.save(entity);
            });

            logger.debug("Persisted {} counters and {} gauges to database",
                    counters.size(), gauges.size());

        } catch (Exception e) {
            logger.error("Error persisting metrics: {}", e.getMessage(), e);
        }
    }

    /**
     * Cleanup old in-memory metrics
     */
    @Scheduled(fixedRate = 600000) // Every 10 minutes
    public void cleanupOldMetrics() {
        LocalDateTime cutoff = LocalDateTime.now().minus(METRICS_RETENTION_MINUTES, ChronoUnit.MINUTES);

        // Remove old timestamp entries and associated data
        timestamps.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(cutoff)) {
                // Also cleanup associated data
                latencyHistograms.remove(entry.getKey());
                performanceTrackers.remove(entry.getKey());
                return true;
            }
            return false;
        });

        logger.debug("Cleaned up old metrics data");
    }

    /**
     * Record system resources periodically
     */
    @Scheduled(fixedRate = 30000) // Every 30 seconds
    public void recordSystemResourcesPeriodically() {
        recordSystemResources();
    }

    // Private helper methods

    private void incrementCounter(String metricName) {
        counters.computeIfAbsent(metricName, k -> new LongAdder()).increment();
    }

    private void updateGauge(String metricName, double value) {
        gauges.put(metricName, value);
    }

    private void recordLatency(String metricName, long latencyMs) {
        latencyHistograms.computeIfAbsent(metricName, k -> new ArrayList<>()).add(latencyMs);

        // Trim if too large
        List<Long> histogram = latencyHistograms.get(metricName);
        if (histogram.size() > MAX_HISTOGRAM_SIZE) {
            histogram.subList(0, histogram.size() - MAX_HISTOGRAM_SIZE).clear();
        }
    }

    private double calculateAverageLatency(List<Long> latencies) {
        if (latencies.isEmpty()) return 0.0;
        return latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private void updateSourceFailureRate(String source) {
        long totalRequests = getCounter("source.requests." + source);
        long failures = getCounter("source.failures." + source);

        if (totalRequests > 0) {
            double failureRate = (failures * 100.0) / totalRequests;
            updateGauge("source.failure_rate." + source, failureRate);
        }
    }

    private void updateApiSuccessRate(String endpoint) {
        long totalCalls = getCounter("api.calls.total." + endpoint);
        long successCalls = getCounter("api.calls.success." + endpoint);

        if (totalCalls > 0) {
            double successRate = (successCalls * 100.0) / totalCalls;
            updateGauge("api.success_rate." + endpoint, successRate);
        }
    }

    // Inner classes for statistics

    public static class PerformanceTracker {
        private final List<Double> dataPoints = new ArrayList<>();
        private final int maxSize = 1000;

        public synchronized void addDataPoint(double value) {
            dataPoints.add(value);
            if (dataPoints.size() > maxSize) {
                dataPoints.subList(0, dataPoints.size() - maxSize).clear();
            }
        }

        public synchronized PerformanceStatistics getStatistics() {
            if (dataPoints.isEmpty()) {
                return new PerformanceStatistics(0.0, 0.0, 0.0, 0.0, 0.0, 0);
            }

            double min = dataPoints.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            double max = dataPoints.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            double avg = dataPoints.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

            // Calculate standard deviation
            double variance = dataPoints.stream()
                    .mapToDouble(x -> Math.pow(x - avg, 2))
                    .average()
                    .orElse(0.0);
            double stdDev = Math.sqrt(variance);

            // Calculate trend (simple linear regression slope)
            double trend = calculateTrend();

            return new PerformanceStatistics(min, max, avg, stdDev, trend, dataPoints.size());
        }

        private double calculateTrend() {
            if (dataPoints.size() < 2) return 0.0;

            int n = dataPoints.size();
            double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;

            for (int i = 0; i < n; i++) {
                sumX += i;
                sumY += dataPoints.get(i);
                sumXY += i * dataPoints.get(i);
                sumXX += i * i;
            }

            return (n * sumXY - sumX * sumY) / (n * sumXX - sumX * sumX);
        }
    }

    // Result classes
    public record LatencyStatistics(
            long min, long max, long avg, long p50, long p95, long p99, int sampleSize
    ) {}

    public record PerformanceStatistics(
            double min, double max, double avg, double stdDev, double trend, int sampleSize
    ) {}

    public record MetricsSummary(
            Map<String, Long> counters,
            Map<String, Double> gauges,
            Map<String, LatencyStatistics> latencies,
            LocalDateTime timestamp
    ) {}
}
