package com.trade.frankenstein.trader.service.market;

import com.trade.frankenstein.trader.dto.AlertDTO;
import com.trade.frankenstein.trader.dto.CandleDTO;
import com.trade.frankenstein.trader.dto.InstrumentTickDTO;
import com.trade.frankenstein.trader.dto.QualityFlags;
import com.trade.frankenstein.trader.model.documents.MarketDataSourceEntity;
import com.trade.frankenstein.trader.repo.documents.MarketDataSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class MarketDataSourceManager {

    private static final Logger logger = LoggerFactory.getLogger(MarketDataSourceManager.class);

    @Autowired
    private MarketDataSourceRepository sourceRepository;

    @Autowired
    private MetricsCollector metricsCollector;

    @Autowired
    private AlertService alertService;

    private final Map<String, LocalDateTime> sourceFailureTracker = new ConcurrentHashMap<>();
    private final Map<String, Integer> sourceAttemptCounter = new ConcurrentHashMap<>();

    // Configuration
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long FAILOVER_COOLDOWN_MINUTES = 5;
    private static final BigDecimal PRICE_DEVIATION_THRESHOLD = BigDecimal.valueOf(0.05); // 5%

    /**
     * Fetches live price from multiple sources with failover
     */
    public InstrumentTickDTO fetchLivePrice(String instrumentKey, List<String> preferredSources) {
        logger.debug("Fetching live price for {} from sources: {}", instrumentKey, preferredSources);

        List<String> availableSources = getAvailableSources(preferredSources);

        for (String source : availableSources) {
            try {
                long startTime = System.currentTimeMillis();
                InstrumentTickDTO tick = fetchFromSource(source, instrumentKey);
                long latency = System.currentTimeMillis() - startTime;

                if (tick != null) {
                    metricsCollector.recordSourceLatency(source, latency);
                    resetFailureCounter(source);

                    // Validate the tick
                    if (isTickValid(tick, source)) {
                        logger.debug("Successfully fetched tick from source: {}", source);
                        return enrichTickWithMetadata(tick, source, latency);
                    }
                }

            } catch (Exception e) {
                logger.warn("Failed to fetch from source {}: {}", source, e.getMessage());
                handleSourceFailure(source, e);
            }
        }

        // All sources failed
        alertService.sendAlert(createSourceFailureAlert(instrumentKey, availableSources));
        throw new RuntimeException("All market data sources failed for instrument: " + instrumentKey);
    }

    /**
     * Fetches candles with multi-source reconciliation
     */
    public List<CandleDTO> fetchCandles(String instrumentKey, String timeframe, int count) {
        logger.debug("Fetching {} {} candles for {}", count, timeframe, instrumentKey);

        List<String> sources = getAvailableSources(getDefaultSources());
        Map<String, List<CandleDTO>> sourceCandles = new HashMap<>();

        // Fetch from multiple sources in parallel
        List<CompletableFuture<Void>> futures = sources.stream()
                .map(source -> CompletableFuture.runAsync(() -> {
                    try {
                        List<CandleDTO> candles = fetchCandlesFromSource(source, instrumentKey, timeframe, count);
                        if (candles != null && !candles.isEmpty()) {
                            sourceCandles.put(source, candles);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to fetch candles from {}: {}", source, e.getMessage());
                        handleSourceFailure(source, e);
                    }
                }))
                .collect(Collectors.toList());

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .join();

        // Reconcile and return best data
        return reconcileCandles(sourceCandles, instrumentKey, timeframe);
    }

    /**
     * Get real-time streaming data
     */
    public void subscribeToTicks(String instrumentKey, TickConsumer consumer) {
        List<String> sources = getAvailableSources(getDefaultSources());

        for (String source : sources) {
            try {
                subscribeToTicksFromSource(source, instrumentKey, (tick) -> {
                    try {
                        if (isTickValid(tick, source)) {
                            consumer.accept(enrichTickWithMetadata(tick, source, 0L));
                        }
                    } catch (Exception e) {
                        logger.error("Error processing tick from {}: {}", source, e.getMessage());
                    }
                });

                logger.info("Successfully subscribed to {} from source {}", instrumentKey, source);
                break; // Use first successful source

            } catch (Exception e) {
                logger.warn("Failed to subscribe to {} from {}: {}", instrumentKey, source, e.getMessage());
                handleSourceFailure(source, e);
            }
        }
    }

    /**
     * Health check for all sources
     */
    public Map<String, SourceHealthStatus> checkSourceHealth() {
        Map<String, SourceHealthStatus> healthMap = new HashMap<>();

        List<MarketDataSourceEntity> sources = sourceRepository.findByEnabledTrue();

        for (MarketDataSourceEntity source : sources) {
            try {
                long startTime = System.currentTimeMillis();
                boolean isHealthy = pingSource(source.getSourceId());
                long responseTime = System.currentTimeMillis() - startTime;

                healthMap.put(source.getSourceId(), new SourceHealthStatus(
                        isHealthy, responseTime, getFailureCount(source.getSourceId()),
                        getLastFailureTime(source.getSourceId())
                ));

            } catch (Exception e) {
                healthMap.put(source.getSourceId(), new SourceHealthStatus(
                        false, -1L, getFailureCount(source.getSourceId()),
                        getLastFailureTime(source.getSourceId())
                ));
            }
        }

        return healthMap;
    }

    // Private helper methods

    private List<String> getAvailableSources(List<String> preferredSources) {
        return preferredSources.stream()
                .filter(this::isSourceAvailable)
                .collect(Collectors.toList());
    }

    private boolean isSourceAvailable(String source) {
        LocalDateTime lastFailure = sourceFailureTracker.get(source);
        if (lastFailure == null) return true;

        return lastFailure.isBefore(LocalDateTime.now().minusMinutes(FAILOVER_COOLDOWN_MINUTES));
    }

    private void handleSourceFailure(String source, Exception e) {
        sourceFailureTracker.put(source, LocalDateTime.now());
        sourceAttemptCounter.merge(source, 1, Integer::sum);

        metricsCollector.recordSourceFailure(source, e.getClass().getSimpleName());

        if (getFailureCount(source) >= MAX_RETRY_ATTEMPTS) {
            alertService.sendAlert(createSourceDownAlert(source, e.getMessage()));
        }
    }

    private void resetFailureCounter(String source) {
        sourceFailureTracker.remove(source);
        sourceAttemptCounter.remove(source);
    }

    private int getFailureCount(String source) {
        return sourceAttemptCounter.getOrDefault(source, 0);
    }

    private LocalDateTime getLastFailureTime(String source) {
        return sourceFailureTracker.get(source);
    }

    private boolean isTickValid(InstrumentTickDTO tick, String source) {
        if (tick == null || tick.price() == null || tick.price().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        // Check if price is reasonable (not extreme deviation)
        return !isPriceOutlier(tick, source);
    }

    private boolean isPriceOutlier(InstrumentTickDTO tick, String source) {
        // Get recent average price and check deviation
        try {
            BigDecimal recentAverage = getRecentAveragePrice(tick.instrumentKey());
            if (recentAverage != null) {
                BigDecimal deviation = tick.price().subtract(recentAverage)
                        .abs().divide(recentAverage, 4, java.math.RoundingMode.HALF_UP);
                return deviation.compareTo(PRICE_DEVIATION_THRESHOLD) > 0;
            }
        } catch (Exception e) {
            logger.debug("Could not validate price outlier: {}", e.getMessage());
        }
        return false;
    }

    private InstrumentTickDTO enrichTickWithMetadata(InstrumentTickDTO tick, String source, long latency) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", source);
        metadata.put("latency_ms", latency);
        metadata.put("fetch_time", LocalDateTime.now());

        QualityFlags qualityFlags = QualityFlags.perfect();

        return new InstrumentTickDTO(
                tick.instrumentKey(), tick.price(), tick.volume(), tick.timestamp(),
                source, qualityFlags, tick.bidPrice(), tick.askPrice(),
                tick.bidSize(), tick.askSize(), metadata
        );
    }

    private List<CandleDTO> reconcileCandles(Map<String, List<CandleDTO>> sourceCandles,
                                             String instrumentKey, String timeframe) {
        if (sourceCandles.isEmpty()) {
            return Collections.emptyList();
        }

        // If only one source, return its data
        if (sourceCandles.size() == 1) {
            return sourceCandles.values().iterator().next();
        }

        // Choose the source with most recent data and highest quality
        String bestSource = sourceCandles.entrySet().stream()
                .max(Comparator.comparing(entry -> {
                    List<CandleDTO> candles = entry.getValue();
                    return candles.isEmpty() ? LocalDateTime.MIN :
                            candles.get(0).timestamp();
                }))
                .map(Map.Entry::getKey)
                .orElse(sourceCandles.keySet().iterator().next());

        return sourceCandles.get(bestSource);
    }

    // Abstract methods that would be implemented based on actual data sources
    private InstrumentTickDTO fetchFromSource(String source, String instrumentKey) {
        // Implementation would depend on specific API (Upstox, Zerodha, etc.)
        // This is a placeholder for the actual implementation
        throw new UnsupportedOperationException("Implement based on actual data source APIs");
    }

    private List<CandleDTO> fetchCandlesFromSource(String source, String instrumentKey,
                                                   String timeframe, int count) {
        // Implementation would depend on specific API
        throw new UnsupportedOperationException("Implement based on actual data source APIs");
    }

    private void subscribeToTicksFromSource(String source, String instrumentKey, TickConsumer consumer) {
        // Implementation would depend on specific WebSocket/streaming API
        throw new UnsupportedOperationException("Implement based on actual streaming APIs");
    }

    private boolean pingSource(String source) {
        // Implementation would check if source is responding
        throw new UnsupportedOperationException("Implement based on actual health check APIs");
    }

    @Cacheable(value = "averagePrices", key = "#instrumentKey")
    private BigDecimal getRecentAveragePrice(String instrumentKey) {
        // Implementation would calculate recent average from repository
        return null;
    }

    private List<String> getDefaultSources() {
        return sourceRepository.findByEnabledTrueOrderByPriority()
                .stream()
                .map(MarketDataSourceEntity::getSourceId)
                .collect(Collectors.toList());
    }

    private AlertDTO createSourceFailureAlert(String instrumentKey, List<String> failedSources) {
        return new AlertDTO(
                UUID.randomUUID().toString(),
                AlertDTO.AlertType.API_FAILURE,
                AlertDTO.AlertSeverity.HIGH,
                instrumentKey,
                "All market data sources failed: " + String.join(", ", failedSources),
                LocalDateTime.now(),
                "MarketDataSourceManager",
                Map.of("failed_sources", failedSources),
                false, null, null
        );
    }

    private AlertDTO createSourceDownAlert(String source, String errorMessage) {
        return new AlertDTO(
                UUID.randomUUID().toString(),
                AlertDTO.AlertType.API_FAILURE,
                AlertDTO.AlertSeverity.MEDIUM,
                null,
                "Market data source down: " + source + " - " + errorMessage,
                LocalDateTime.now(),
                "MarketDataSourceManager",
                Map.of("source", source, "error", errorMessage),
                false, null, null
        );
    }

    @FunctionalInterface
    public interface TickConsumer {
        void accept(InstrumentTickDTO tick);
    }

    public record SourceHealthStatus(
            boolean isHealthy,
            long responseTimeMs,
            int failureCount,
            LocalDateTime lastFailureTime
    ) {}
}
