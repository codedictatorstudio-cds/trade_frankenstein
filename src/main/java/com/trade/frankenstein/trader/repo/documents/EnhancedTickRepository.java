package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.InstrumentTickEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EnhancedTickRepository extends MongoRepository<InstrumentTickEntity, String> {

    // Basic queries
    List<InstrumentTickEntity> findByInstrumentKeyAndTimestampBetweenOrderByTimestampDesc(
            String instrumentKey, LocalDateTime start, LocalDateTime end);

    Optional<InstrumentTickEntity> findTopByInstrumentKeyOrderByTimestampDesc(String instrumentKey);

    List<InstrumentTickEntity> findTop100ByInstrumentKeyOrderByTimestampDesc(String instrumentKey);

    // Quality-based queries
    @Query("{'qualityScore': {'$lt': ?0}}")
    List<InstrumentTickEntity> findLowQualityTicks(BigDecimal threshold);

    @Query("{'hasAnomaly': true, 'timestamp': {'$gte': ?0}}")
    List<InstrumentTickEntity> findAnomalousTicksSince(LocalDateTime since);

    @Query("{'instrumentKey': ?0, 'qualityScore': {'$gte': ?1}, 'timestamp': {'$gte': ?2}}")
    List<InstrumentTickEntity> findHighQualityTicksSince(String instrumentKey,
                                                         BigDecimal minQuality,
                                                         LocalDateTime since);

    // Source-specific queries
    List<InstrumentTickEntity> findBySourceAndTimestampBetweenOrderByTimestampDesc(
            String source, LocalDateTime start, LocalDateTime end);

    @Query(value = "{'instrumentKey': ?0}", fields = "{'source': 1}")
    List<String> findDistinctSourcesByInstrumentKey(String instrumentKey);

    // Statistical aggregations
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timestamp': {'$gte': ?1}, 'qualityScore': {'$gte': ?2}}}",
            "{'$group': {'_id': null, 'avgPrice': {'$avg': '$price'}}}"
    })
    Optional<BigDecimal> findAveragePrice(String instrumentKey, LocalDateTime since, BigDecimal minQuality);

    @Query(value = "{'instrumentKey': ?0, 'timestamp': {'$gte': ?1}}", count = true)
    Long countTicksSince(String instrumentKey, LocalDateTime since);

    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0}}",
            "{'$group': {'_id': null, 'maxTimestamp': {'$max': '$timestamp'}}}"
    })
    Optional<LocalDateTime> findLatestTimestamp(String instrumentKey);

    // Performance queries
    @Query("{'latencyMs': {'$gt': ?0}}")
    List<InstrumentTickEntity> findHighLatencyTicks(Long maxLatency);

    @Aggregation(pipeline = {
            "{'$match': {'timestamp': {'$gte': ?0}}}",
            "{'$group': {'_id': null, 'avgLatency': {'$avg': '$latencyMs'}}}"
    })
    Optional<Double> findAverageLatencySince(LocalDateTime since);

    // Cleanup operations
    void deleteByTimestampBefore(LocalDateTime cutoff);

    @Query("{'instrumentKey': {'$in': ?0}, 'timestamp': {'$gte': ?1}}")
    List<InstrumentTickEntity> findLatestTicksForInstruments(List<String> instrumentKeys,
                                                             LocalDateTime since);

    // MongoDB-specific aggregation queries
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timestamp': {'$gte': ?1}}}",
            "{'$group': {" +
                    "'_id': null," +
                    "'count': {'$sum': 1}," +
                    "'avgPrice': {'$avg': '$price'}," +
                    "'minPrice': {'$min': '$price'}," +
                    "'maxPrice': {'$max': '$price'}," +
                    "'avgQuality': {'$avg': '$qualityScore'}," +
                    "'anomalyCount': {'$sum': {'$cond': ['$hasAnomaly', 1, 0]}}" +
                    "}}"
    })
    Optional<TickStatistics> getTickStatistics(String instrumentKey, LocalDateTime since);

    @Aggregation(pipeline = {
            "{'$match': {'timestamp': {'$gte': ?0}}}",
            "{'$group': {'_id': '$source', 'count': {'$sum': 1}, 'avgLatency': {'$avg': '$latencyMs'}}}",
            "{'$sort': {'count': -1}}"
    })
    List<SourceStatistics> getSourceStatistics(LocalDateTime since);

    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timestamp': {'$gte': ?1}}}",
            "{'$sort': {'timestamp': 1}}",
            "{'$group': {" +
                    "'_id': null," +
                    "'prices': {'$push': '$price'}," +
                    "'volumes': {'$push': '$volume'}" +
                    "}}"
    })
    Optional<PriceVolumeHistory> getPriceVolumeHistory(String instrumentKey, LocalDateTime since);

    // Real-time queries with pagination
    List<InstrumentTickEntity> findByInstrumentKeyOrderByTimestampDesc(String instrumentKey, Pageable pageable);

    @Query("{'instrumentKey': ?0, 'qualityScore': {'$gte': ?1}}")
    List<InstrumentTickEntity> findByInstrumentKeyAndQualityScoreGreaterThanEqual(
            String instrumentKey, BigDecimal minQuality, Pageable pageable);

    // Result classes for aggregations
    interface TickStatistics {
        Long getCount();

        BigDecimal getAvgPrice();

        BigDecimal getMinPrice();

        BigDecimal getMaxPrice();

        BigDecimal getAvgQuality();

        Long getAnomalyCount();
    }

    interface SourceStatistics {
        String getId(); // source name

        Long getCount();

        Double getAvgLatency();
    }

    interface PriceVolumeHistory {
        List<BigDecimal> getPrices();

        List<Long> getVolumes();
    }
}
