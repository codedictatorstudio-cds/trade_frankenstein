package com.trade.frankenstein.trader.service.options;

import com.trade.frankenstein.trader.dto.RealTimeGreeksDTO;
import com.trade.frankenstein.trader.model.documents.GreeksSnapshot;
import com.trade.frankenstein.trader.repo.documents.GreeksSnapshotRepo;
import com.trade.frankenstein.trader.service.GreeksCalculationService;
import com.trade.frankenstein.trader.service.UpstoxService;
import com.upstox.api.MarketQuoteOptionGreekV3;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GreeksCalculationServiceImpl implements GreeksCalculationService {

    @Autowired
    private UpstoxService upstoxService;

    @Autowired
    private GreeksSnapshotRepo greeksSnapshotRepo;

    private static final int MAX_BATCH_SIZE = 50; // As per Upstox API limit
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 1000;

    @Override
    public Map<String, MarketQuoteOptionGreekV3> calculateGreeksBulk(List<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) {
            log.warn("Empty or null instrument keys provided");
            return new HashMap<>();
        }

        Map<String, MarketQuoteOptionGreekV3> results = new HashMap<>();

        try {
            log.info("Calculating Greeks for {} instruments", instrumentKeys.size());

            // Process in batches to avoid API limits
            List<List<String>> batches = partitionList(instrumentKeys, MAX_BATCH_SIZE);
            log.debug("Split into {} batches", batches.size());

            for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
                List<String> batch = batches.get(batchIndex);
                log.debug("Processing batch {}/{} with {} instruments",
                        batchIndex + 1, batches.size(), batch.size());

                int retryCount = 0;
                boolean success = false;

                while (!success && retryCount < MAX_RETRIES) {
                    try {
                        // Convert List<String> to comma-separated string for Upstox API
                        String instrumentKeysParam = String.join(",", batch);

                        // Call the actual UpstoxService method
                        Map<String, MarketQuoteOptionGreekV3> batchResults = upstoxService.getOptionGreeks(instrumentKeysParam).getData();

                        if (batchResults == null || batchResults.isEmpty()) {
                            log.warn("No Greeks data returned for batch: {}", batch);
                            success = true; // Don't retry for empty results
                            continue;
                        }

                        // Validate and add each result
                        int validCount = 0;
                        for (Map.Entry<String, MarketQuoteOptionGreekV3> entry : batchResults.entrySet()) {
                            try {
                                validateGreeksData(entry.getValue());
                                results.put(entry.getKey(), entry.getValue());
                                validCount++;
                            } catch (IllegalArgumentException e) {
                                log.warn("Invalid Greeks data for instrument {}: {}", entry.getKey(), e.getMessage());
                            }
                        }

                        log.debug("Successfully processed {}/{} instruments from batch {}",
                                validCount, batchResults.size(), batchIndex + 1);
                        success = true;

                    } catch (Exception e) {
                        retryCount++;
                        log.warn("Failed to fetch Greeks for batch {} (attempt {}/{}): {}",
                                batchIndex + 1, retryCount, MAX_RETRIES, e.getMessage());

                        if (retryCount < MAX_RETRIES) {
                            try {
                                long delay = RETRY_DELAY_MS * retryCount; // Exponential backoff
                                log.debug("Retrying after {} ms", delay);
                                Thread.sleep(delay);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                log.error("Thread interrupted during retry delay");
                                break;
                            }
                        } else {
                            log.error("Max retries exceeded for batch {}", batchIndex + 1);
                        }
                    }
                }

                // Small delay between batches to avoid rate limiting
                if (batchIndex < batches.size() - 1) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log.info("Successfully calculated Greeks for {}/{} instruments",
                    results.size(), instrumentKeys.size());

        } catch (Exception e) {
            log.error("Unexpected error calculating Greeks in bulk", e);
        }

        return results;
    }

    @Override
    public MarketQuoteOptionGreekV3 calculateGreeks(String instrumentKey) {
        if (instrumentKey == null || instrumentKey.trim().isEmpty()) {
            log.warn("Empty or null instrument key provided");
            return null;
        }

        try {
            log.debug("Calculating Greeks for single instrument: {}", instrumentKey);

            // Call UpstoxService with single instrument key
            Map<String, MarketQuoteOptionGreekV3> result = upstoxService.getOptionGreeks(instrumentKey).getData();

            if (result == null || result.isEmpty()) {
                log.warn("No Greeks data returned for instrument: {}", instrumentKey);
                return null;
            }

            MarketQuoteOptionGreekV3 greeks = result.get(instrumentKey);

            if (greeks != null) {
                validateGreeksData(greeks);
                log.debug("Successfully calculated Greeks for instrument: {}", instrumentKey);
            } else {
                log.warn("Greeks data is null for instrument: {}", instrumentKey);
            }

            return greeks;

        } catch (IllegalArgumentException e) {
            log.warn("Invalid Greeks data for instrument {}: {}", instrumentKey, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Error calculating Greeks for instrument: {}", instrumentKey, e);
            return null;
        }
    }

    @Override
    @Async("taskExecutor")
    public CompletableFuture<Map<String, RealTimeGreeksDTO>> calculateGreeksAsync(List<String> instrumentKeys) {
        try {
            log.info("Starting async Greeks calculation for {} instruments",
                    instrumentKeys != null ? instrumentKeys.size() : 0);

            Map<String, MarketQuoteOptionGreekV3> greeksMap = calculateGreeksBulk(instrumentKeys);
            Map<String, RealTimeGreeksDTO> result = new HashMap<>();

            for (Map.Entry<String, MarketQuoteOptionGreekV3> entry : greeksMap.entrySet()) {
                try {
                    RealTimeGreeksDTO dto = convertToRealTimeDTO(entry.getKey(), entry.getValue());
                    if (dto != null) {
                        result.put(entry.getKey(), dto);
                    }
                } catch (Exception e) {
                    log.warn("Error converting Greeks to DTO for instrument {}: {}",
                            entry.getKey(), e.getMessage());
                }
            }

            // Enrich with historical context
            result = enrichWithHistoricalContext(result);

            log.info("Completed async Greeks calculation. Processed {}/{} instruments",
                    result.size(), greeksMap.size());

            return CompletableFuture.completedFuture(result);

        } catch (Exception e) {
            log.error("Error in async Greeks calculation", e);
            return CompletableFuture.completedFuture(new HashMap<>());
        }
    }

    @Override
    public GreeksSnapshot saveGreeksSnapshot(String instrumentKey, MarketQuoteOptionGreekV3 greeks) {
        if (instrumentKey == null || instrumentKey.trim().isEmpty()) {
            log.warn("Empty or null instrument key provided for snapshot");
            return null;
        }

        if (greeks == null) {
            log.warn("Null Greeks data provided for snapshot: {}", instrumentKey);
            return null;
        }

        try {
            validateGreeksData(greeks);

            GreeksSnapshot snapshot = GreeksSnapshot.builder()
                    .instrumentKey(instrumentKey)
                    .timestamp(Instant.now())
                    .delta(safeBigDecimal(greeks.getDelta()))
                    .gamma(safeBigDecimal(greeks.getGamma()))
                    .theta(safeBigDecimal(greeks.getTheta()))
                    .vega(safeBigDecimal(greeks.getVega()))
                    .impliedVolatility(safeBigDecimal(greeks.getIv()))
                    .optionPrice(safeBigDecimal(greeks.getLastPrice()))
                    .volume(safeLong(greeks.getVolume()))
                    .openInterest(safeDoubleToLong(greeks.getOi()))
                    .dataQuality(calculateDataQuality(greeks))
                    .pricingModel("UPSTOX_API")
                    .build();

            // Calculate derived metrics
            enrichSnapshotWithDerivedMetrics(snapshot, greeks);

            GreeksSnapshot saved = greeksSnapshotRepo.save(snapshot);
            log.debug("Saved Greeks snapshot for instrument: {}", instrumentKey);

            return saved;

        } catch (IllegalArgumentException e) {
            log.warn("Invalid Greeks data for snapshot {}: {}", instrumentKey, e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Error saving Greeks snapshot for instrument: {}", instrumentKey, e);
            return null;
        }
    }

    @Override
    public List<GreeksSnapshot> getHistoricalGreeks(String instrumentKey, int days) {
        if (instrumentKey == null || instrumentKey.trim().isEmpty()) {
            log.warn("Empty or null instrument key provided for historical Greeks");
            return Collections.emptyList();
        }

        if (days <= 0) {
            log.warn("Invalid days parameter: {}", days);
            return Collections.emptyList();
        }

        try {
            Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
            List<GreeksSnapshot> snapshots = greeksSnapshotRepo
                    .findByInstrumentKeyAndTimestampAfterOrderByTimestampDesc(instrumentKey, since);

            log.debug("Found {} historical Greeks snapshots for instrument {} over {} days",
                    snapshots.size(), instrumentKey, days);

            return snapshots;

        } catch (Exception e) {
            log.error("Error fetching historical Greeks for instrument: {}", instrumentKey, e);
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, RealTimeGreeksDTO> enrichWithHistoricalContext(Map<String, RealTimeGreeksDTO> greeksMap) {
        if (greeksMap == null || greeksMap.isEmpty()) {
            return greeksMap != null ? greeksMap : new HashMap<>();
        }

        try {
            log.debug("Enriching {} instruments with historical context", greeksMap.size());

            for (Map.Entry<String, RealTimeGreeksDTO> entry : greeksMap.entrySet()) {
                String instrumentKey = entry.getKey();
                RealTimeGreeksDTO dto = entry.getValue();

                try {
                    // Get historical data for 24h comparison
                    List<GreeksSnapshot> historical = getHistoricalGreeks(instrumentKey, 1);

                    if (!historical.isEmpty()) {
                        GreeksSnapshot yesterday = historical.get(0);

                        // Calculate 24h changes
                        calculateChanges(dto, yesterday);
                    }

                    // Calculate percentile rankings based on 30-day history
                    calculatePercentileRankings(dto, instrumentKey);

                } catch (Exception e) {
                    log.warn("Error enriching historical context for instrument {}: {}",
                            instrumentKey, e.getMessage());
                }
            }

            log.debug("Completed historical context enrichment");

        } catch (Exception e) {
            log.error("Error enriching Greeks with historical context", e);
        }

        return greeksMap;
    }

    @Override
    public void validateGreeksData(MarketQuoteOptionGreekV3 greeks) throws IllegalArgumentException {
        if (greeks == null) {
            throw new IllegalArgumentException("Greeks data cannot be null");
        }

        // Validate delta range (-1 to 1 for options)
        if (greeks.getDelta() != null) {
            double delta = greeks.getDelta();
            if (Math.abs(delta) > 1.0) {
                throw new IllegalArgumentException("Delta out of valid range: " + delta);
            }
        }

        // Validate gamma (should be positive)
        if (greeks.getGamma() != null) {
            double gamma = greeks.getGamma();
            if (gamma < 0) {
                throw new IllegalArgumentException("Gamma cannot be negative: " + gamma);
            }
        }

        // Validate IV (should be positive and reasonable)
        if (greeks.getIv() != null) {
            double iv = greeks.getIv();
            if (iv < 0 || iv > 10.0) { // 1000% IV is unrealistic
                throw new IllegalArgumentException("Implied volatility out of valid range: " + iv);
            }
        }

        // Validate last price (should be positive)
        if (greeks.getLastPrice() != null) {
            double price = greeks.getLastPrice();
            if (price < 0) {
                throw new IllegalArgumentException("Last price cannot be negative: " + price);
            }
        }

        // Check for NaN or infinite values
        validateFiniteValue(greeks.getDelta(), "Delta");
        validateFiniteValue(greeks.getGamma(), "Gamma");
        validateFiniteValue(greeks.getTheta(), "Theta");
        validateFiniteValue(greeks.getVega(), "Vega");
        validateFiniteValue(greeks.getIv(), "Implied Volatility");
        validateFiniteValue(greeks.getLastPrice(), "Last Price");
        validateFiniteValue(greeks.getCp(), "Close Price");
        validateFiniteValue(greeks.getOi(), "Open Interest");
    }

    // Private helper methods

    private <T> List<List<T>> partitionList(List<T> list, int size) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    private RealTimeGreeksDTO convertToRealTimeDTO(String instrumentKey, MarketQuoteOptionGreekV3 greeks) {
        if (instrumentKey == null || greeks == null) {
            return null;
        }

        try {
            RealTimeGreeksDTO dto = RealTimeGreeksDTO.builder()
                    .instrumentKey(instrumentKey)
                    .timestamp(Instant.now())
                    .delta(safeBigDecimal(greeks.getDelta()))
                    .gamma(safeBigDecimal(greeks.getGamma()))
                    .theta(safeBigDecimal(greeks.getTheta()))
                    .vega(safeBigDecimal(greeks.getVega()))
                    .impliedVolatility(safeBigDecimal(greeks.getIv()))
                    .lastPrice(safeBigDecimal(greeks.getLastPrice()))
                    .volume(safeLong(greeks.getVolume()))
                    .openInterest(safeDoubleToLong(greeks.getOi()))
                    .dataQuality(calculateDataQuality(greeks))
                    .dataSource("UPSTOX")
                    .pricingModel("BLACK_SCHOLES")
                    .build();

            // Calculate derived metrics
            enrichDTOWithDerivedMetrics(dto);

            return dto;

        } catch (Exception e) {
            log.error("Error converting Greeks to DTO for instrument: {}", instrumentKey, e);
            return null;
        }
    }

    private void enrichSnapshotWithDerivedMetrics(GreeksSnapshot snapshot, MarketQuoteOptionGreekV3 greeks) {
        try {
            // Determine liquidity based on volume and OI
            snapshot.setIsLiquid(isLiquid(snapshot));

            // Calculate dollar Greeks (assuming contract multiplier of 100)
            BigDecimal multiplier = new BigDecimal("100");

            if (snapshot.getDelta() != null) {
                snapshot.setDollarDelta(snapshot.getDelta().multiply(multiplier));
            }

            if (snapshot.getGamma() != null) {
                snapshot.setDollarGamma(snapshot.getGamma().multiply(multiplier));
            }

            if (snapshot.getTheta() != null) {
                snapshot.setDollarTheta(snapshot.getTheta().multiply(multiplier));
            }

            if (snapshot.getVega() != null) {
                BigDecimal vegaMultiplier = multiplier.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
                snapshot.setDollarVega(snapshot.getVega().multiply(vegaMultiplier));
            }

            // Calculate moneyness and other derived metrics
            if (snapshot.getOptionPrice() != null && snapshot.getOptionPrice().compareTo(BigDecimal.ZERO) > 0) {
                // Basic elasticity calculation (Delta * Underlying / Option Price)
                // Note: We don't have underlying price, so this is simplified
                if (snapshot.getDelta() != null) {
                    BigDecimal elasticity = snapshot.getDelta().divide(snapshot.getOptionPrice(), 4, RoundingMode.HALF_UP);
                    snapshot.setElasticity(elasticity);
                }
            }

        } catch (Exception e) {
            log.warn("Error enriching snapshot with derived metrics: {}", e.getMessage());
        }
    }

    private void enrichDTOWithDerivedMetrics(RealTimeGreeksDTO dto) {
        try {
            // Calculate dollar Greeks
            BigDecimal multiplier = new BigDecimal("100");

            if (dto.getDelta() != null) {
                dto.setDollarDelta(dto.getDelta().multiply(multiplier));
            }

            if (dto.getGamma() != null) {
                dto.setDollarGamma(dto.getGamma().multiply(multiplier));
            }

            if (dto.getTheta() != null) {
                dto.setDollarTheta(dto.getTheta().multiply(multiplier));
            }

            if (dto.getVega() != null) {
                BigDecimal vegaMultiplier = multiplier.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
                dto.setDollarVega(dto.getVega().multiply(vegaMultiplier));
            }

            // Set liquidity flag
            dto.setIsLiquid(isLiquidDTO(dto));

        } catch (Exception e) {
            log.warn("Error enriching DTO with derived metrics: {}", e.getMessage());
        }
    }

    private BigDecimal calculateDataQuality(MarketQuoteOptionGreekV3 greeks) {
        double quality = 1.0;

        try {
            // Penalize missing Greeks
            if (greeks.getDelta() == null) quality -= 0.2;
            if (greeks.getGamma() == null) quality -= 0.2;
            if (greeks.getTheta() == null) quality -= 0.1;
            if (greeks.getVega() == null) quality -= 0.1;
            if (greeks.getIv() == null) quality -= 0.2;

            // Penalize missing market data
            if (greeks.getLastPrice() == null) quality -= 0.1;
            if (greeks.getVolume() == null) quality -= 0.05;
            if (greeks.getOi() == null) quality -= 0.05;

        } catch (Exception e) {
            log.warn("Error calculating data quality: {}", e.getMessage());
            quality = 0.5; // Default to medium quality on error
        }

        return new BigDecimal(Math.max(0.0, quality)).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isLiquid(GreeksSnapshot snapshot) {
        try {
            Long volume = snapshot.getVolume();
            Long oi = snapshot.getOpenInterest();

            return (volume != null && volume > 100) ||
                    (oi != null && oi > 1000);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLiquidDTO(RealTimeGreeksDTO dto) {
        try {
            Long volume = dto.getVolume();
            Long oi = dto.getOpenInterest();

            return (volume != null && volume > 100) ||
                    (oi != null && oi > 1000);
        } catch (Exception e) {
            return false;
        }
    }

    private void calculateChanges(RealTimeGreeksDTO dto, GreeksSnapshot yesterday) {
        try {
            // Calculate 24h changes
            if (yesterday.getDelta() != null && dto.getDelta() != null) {
                dto.setDeltaChange24h(dto.getDelta().subtract(yesterday.getDelta()));
            }

            if (yesterday.getGamma() != null && dto.getGamma() != null) {
                dto.setGammaChange24h(dto.getGamma().subtract(yesterday.getGamma()));
            }

            if (yesterday.getTheta() != null && dto.getTheta() != null) {
                dto.setThetaChange24h(dto.getTheta().subtract(yesterday.getTheta()));
            }

            if (yesterday.getVega() != null && dto.getVega() != null) {
                dto.setVegaChange24h(dto.getVega().subtract(yesterday.getVega()));
            }

            if (yesterday.getImpliedVolatility() != null && dto.getImpliedVolatility() != null) {
                dto.setIvChange24h(dto.getImpliedVolatility().subtract(yesterday.getImpliedVolatility()));
            }

            if (yesterday.getOptionPrice() != null && dto.getLastPrice() != null) {
                dto.setPriceChange24h(dto.getLastPrice().subtract(yesterday.getOptionPrice()));
            }

        } catch (Exception e) {
            log.warn("Error calculating changes: {}", e.getMessage());
        }
    }

    private void calculatePercentileRankings(RealTimeGreeksDTO dto, String instrumentKey) {
        try {
            List<GreeksSnapshot> historical = getHistoricalGreeks(instrumentKey, 30); // 30 days
            if (historical.size() < 10) {
                log.debug("Insufficient historical data for percentile calculation: {} snapshots", historical.size());
                return; // Need sufficient data
            }

            // Calculate delta percentile
            if (dto.getDelta() != null) {
                List<BigDecimal> deltas = historical.stream()
                        .map(GreeksSnapshot::getDelta)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                dto.setDeltaPercentile(calculatePercentile(dto.getDelta(), deltas));
            }

            // Calculate gamma percentile
            if (dto.getGamma() != null) {
                List<BigDecimal> gammas = historical.stream()
                        .map(GreeksSnapshot::getGamma)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                dto.setGammaPercentile(calculatePercentile(dto.getGamma(), gammas));
            }

            // Calculate theta percentile
            if (dto.getTheta() != null) {
                List<BigDecimal> thetas = historical.stream()
                        .map(GreeksSnapshot::getTheta)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                dto.setThetaPercentile(calculatePercentile(dto.getTheta(), thetas));
            }

            // Calculate vega percentile
            if (dto.getVega() != null) {
                List<BigDecimal> vegas = historical.stream()
                        .map(GreeksSnapshot::getVega)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                dto.setVegaPercentile(calculatePercentile(dto.getVega(), vegas));
            }

            // Calculate IV percentile
            if (dto.getImpliedVolatility() != null) {
                List<BigDecimal> ivs = historical.stream()
                        .map(GreeksSnapshot::getImpliedVolatility)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                dto.setIvPercentile(calculatePercentile(dto.getImpliedVolatility(), ivs));
            }

        } catch (Exception e) {
            log.warn("Error calculating percentile rankings for {}: {}", instrumentKey, e.getMessage());
        }
    }

    private BigDecimal calculatePercentile(BigDecimal value, List<BigDecimal> historicalValues) {
        if (historicalValues.isEmpty() || value == null) {
            return new BigDecimal("50"); // Default to 50th percentile
        }

        try {
            long countBelow = historicalValues.stream()
                    .mapToLong(v -> value.compareTo(v) > 0 ? 1 : 0)
                    .sum();

            double percentile = (double) countBelow / historicalValues.size() * 100;
            return new BigDecimal(percentile).setScale(1, RoundingMode.HALF_UP);

        } catch (Exception e) {
            log.warn("Error calculating percentile: {}", e.getMessage());
            return new BigDecimal("50");
        }
    }

    private void validateFiniteValue(Object value, String fieldName) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException(fieldName + " contains invalid value: " + d);
            }
        }
    }

    private BigDecimal safeBigDecimal(Object value) {
        try {
            return value != null ? new BigDecimal(value.toString()) : null;
        } catch (NumberFormatException e) {
            log.warn("Error converting to BigDecimal: {} -> {}", value, e.getMessage());
            return null;
        }
    }

    private Long safeLong(Object value) {
        try {
            if (value == null) return null;
            if (value instanceof Long) return (Long) value;
            if (value instanceof Number) return ((Number) value).longValue();
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Error converting to Long: {} -> {}", value, e.getMessage());
            return null;
        }
    }

    private Long safeDoubleToLong(Double value) {
        try {
            return value != null ? value.longValue() : null;
        } catch (Exception e) {
            log.warn("Error converting Double to Long: {} -> {}", value, e.getMessage());
            return null;
        }
    }
}
