package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.MarketSignalEntity;
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
public interface MarketSignalRepository extends MongoRepository<MarketSignalEntity, String> {

    // Basic signal queries
    List<MarketSignalEntity> findByInstrumentKeyAndSignalTypeOrderByTimestampDesc(
            String instrumentKey, String signalType);

    List<MarketSignalEntity> findBySignalTypeAndTimestampAfterOrderByTimestampDesc(
            String signalType, LocalDateTime since);

    Optional<MarketSignalEntity> findTopByInstrumentKeyAndSignalTypeOrderByTimestampDesc(
            String instrumentKey, String signalType);

    // Confidence-based queries
    @Query("{'confidence': {'$gte': ?0}, 'isActive': true}")
    List<MarketSignalEntity> findHighConfidenceSignals(BigDecimal minConfidence, Pageable pageable);

    @Query("{'instrumentKey': ?0, 'confidence': {'$gte': ?1}, 'isActive': true}")
    List<MarketSignalEntity> findHighConfidenceSignalsForInstrument(
            String instrumentKey, BigDecimal minConfidence, Pageable pageable);

    // Active signals
    @Query("{'isActive': true, '$or': [{'expiresAt': null}, {'expiresAt': {'$gt': ?0}}]}")
    List<MarketSignalEntity> findActiveSignals(LocalDateTime now);

    @Query("{'instrumentKey': ?0, 'isActive': true, '$or': [{'expiresAt': null}, {'expiresAt': {'$gt': ?1}}]}")
    List<MarketSignalEntity> findActiveSignalsForInstrument(String instrumentKey, LocalDateTime now);

    // Direction-based queries
    @Query("{'direction': ?0, 'isActive': true}")
    List<MarketSignalEntity> findByDirectionAndIsActiveTrue(String direction, Pageable pageable);

    @Query("{'instrumentKey': ?0, 'direction': ?1, 'isActive': true, 'confidence': {'$gte': ?2}}")
    List<MarketSignalEntity> findDirectionalSignals(String instrumentKey, String direction,
                                                    BigDecimal minConfidence, Pageable pageable);

    // Timeframe queries
    @Query("{'timeframe': ?0, 'isActive': true}")
    List<MarketSignalEntity> findByTimeframeAndIsActiveTrue(String timeframe, Pageable pageable);

    @Query("{'instrumentKey': ?0, 'timeframe': {'$in': ?1}, 'isActive': true}")
    List<MarketSignalEntity> findByInstrumentKeyAndTimeframesAndIsActiveTrue(
            String instrumentKey, List<String> timeframes);

    // Statistical aggregations
    @Aggregation(pipeline = {
            "{'$match': {'signalType': ?0, 'timestamp': {'$gte': ?1}}}",
            "{'$group': {'_id': null, 'avgConfidence': {'$avg': '$confidence'}}}"
    })
    Optional<BigDecimal> findAverageConfidenceByType(String signalType, LocalDateTime since);

    @Query(value = "{'instrumentKey': ?0, 'signalType': ?1, 'timestamp': {'$gte': ?2}}", count = true)
    Long countSignalsByType(String instrumentKey, String signalType, LocalDateTime since);

    // Expiry management
    @Query("{'expiresAt': {'$lte': ?0}, 'isActive': true}")
    List<MarketSignalEntity> findExpiredSignals(LocalDateTime now);

    // Batch operations
    @Query("{'instrumentKey': {'$in': ?0}, 'isActive': true}")
    List<MarketSignalEntity> findActiveSignalsForInstruments(List<String> instrumentKeys, Pageable pageable);

    // Performance analysis
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timestamp': {'$gte': ?1}}}",
            "{'$group': {" +
                    "'_id': '$signalType'," +
                    "'count': {'$sum': 1}," +
                    "'avgConfidence': {'$avg': '$confidence'}," +
                    "'avgStrength': {'$avg': '$strength'}," +
                    "'highConfidenceCount': {'$sum': {'$cond': [{'$gte': ['$confidence', 0.8]}, 1, 0]}}" +
                    "}}",
            "{'$sort': {'avgConfidence': -1}}"
    })
    List<SignalPerformance> getSignalPerformanceByType(String instrumentKey, LocalDateTime since);

    // Feature analysis
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'isActive': true}}",
            "{'$unwind': '$featureVector'}",
            "{'$group': {" +
                    "'_id': '$featureVector.k'," +
                    "'avgValue': {'$avg': '$featureVector.v'}," +
                    "'count': {'$sum': 1}" +
                    "}}",
            "{'$sort': {'count': -1}}"
    })
    List<FeatureImportance> getFeatureImportance(String instrumentKey);

    // Signal strength distribution
    @Aggregation(pipeline = {
            "{'$match': {'timestamp': {'$gte': ?0}}}",
            "{'$bucket': {" +
                    "'groupBy': '$strength'," +
                    "'boundaries': [0, 0.2, 0.4, 0.6, 0.8, 1.0]," +
                    "'default': 'other'," +
                    "'output': {'count': {'$sum': 1}, 'avgConfidence': {'$avg': '$confidence'}}" +
                    "}}"
    })
    List<StrengthDistribution> getStrengthDistribution(LocalDateTime since);

    // Cross-signal analysis
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timestamp': {'$gte': ?1}}}",
            "{'$group': {" +
                    "'_id': {'signalType': '$signalType', 'direction': '$direction'}," +
                    "'count': {'$sum': 1}," +
                    "'avgConfidence': {'$avg': '$confidence'}," +
                    "'avgStrength': {'$avg': '$strength'}" +
                    "}}",
            "{'$sort': {'count': -1}}"
    })
    List<CrossSignalAnalysis> getCrossSignalAnalysis(String instrumentKey, LocalDateTime since);

    // Real-time signal monitoring
    @Query("{'timestamp': {'$gte': ?0}, 'isActive': true}")
    List<MarketSignalEntity> findRecentActiveSignals(LocalDateTime since, Pageable pageable);

    @Query("{'instrumentKey': ?0, 'confidence': {'$gte': ?1}, 'timestamp': {'$gte': ?2}}")
    List<MarketSignalEntity> findRecentHighConfidenceSignals(
            String instrumentKey, BigDecimal minConfidence, LocalDateTime since, Pageable pageable);

    // Cleanup operations
    void deleteByTimestampBeforeAndIsActiveFalse(LocalDateTime cutoff);

    @Query(value = "{'expiresAt': {'$lt': ?0}}", delete = true)
    void deleteExpiredSignals(LocalDateTime cutoff);

    // Result interfaces for aggregations
    interface SignalPerformance {
        String getId(); // signalType

        Long getCount();

        BigDecimal getAvgConfidence();

        BigDecimal getAvgStrength();

        Long getHighConfidenceCount();
    }

    interface FeatureImportance {
        String getId(); // feature name

        BigDecimal getAvgValue();

        Long getCount();
    }

    interface StrengthDistribution {
        Object getId(); // strength bucket

        Long getCount();

        BigDecimal getAvgConfidence();
    }

    interface CrossSignalAnalysis {
        CrossSignalId getId();

        Long getCount();

        BigDecimal getAvgConfidence();

        BigDecimal getAvgStrength();

        interface CrossSignalId {
            String getSignalType();

            String getDirection();
        }
    }
}
