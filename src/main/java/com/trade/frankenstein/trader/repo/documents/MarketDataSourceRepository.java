package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.MarketDataSourceEntity;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketDataSourceRepository extends MongoRepository<MarketDataSourceEntity, String> {

    // Basic queries
    Optional<MarketDataSourceEntity> findBySourceId(String sourceId);

    List<MarketDataSourceEntity> findByEnabledTrueOrderByPriority();

    List<MarketDataSourceEntity> findByEnabledTrue();

    List<MarketDataSourceEntity> findByIsHealthyTrueAndEnabledTrueOrderByPriority();

    // Health-based queries
    @Query("{'isHealthy': false, 'enabled': true}")
    List<MarketDataSourceEntity> findUnhealthyEnabledSources();

    @Query("{'failureCount': {'$gte': ?0}}")
    List<MarketDataSourceEntity> findSourcesWithHighFailureCount(Integer threshold);

    @Query("{'lastFailure': {'$gte': ?0}}")
    List<MarketDataSourceEntity> findSourcesWithRecentFailures(LocalDateTime since);

    // Support queries
    @Query("{'supportedInstruments': ?0, 'enabled': true}")
    List<MarketDataSourceEntity> findSourcesSupportingInstrument(String instrumentKey);

    @Query("{'supportedTimeframes': ?0, 'enabled': true}")
    List<MarketDataSourceEntity> findSourcesSupportingTimeframe(String timeframe);

    @Query("{'supportedInstruments': ?0, 'supportedTimeframes': ?1, 'enabled': true, 'isHealthy': true}")
    List<MarketDataSourceEntity> findHealthySourcesSupportingInstrumentAndTimeframe(
            String instrumentKey, String timeframe);

    // Priority and configuration queries
    List<MarketDataSourceEntity> findByPriorityLessThanEqualOrderByPriority(Integer maxPriority);

    @Query("{'configuration.?0': {'$exists': true}}")
    List<MarketDataSourceEntity> findSourcesWithConfiguration(String configKey);

    // Statistics and monitoring
    @Aggregation(pipeline = {
            "{'$group': {" +
                    "'_id': null," +
                    "'totalSources': {'$sum': 1}," +
                    "'enabledSources': {'$sum': {'$cond': ['$enabled', 1, 0]}}," +
                    "'healthySources': {'$sum': {'$cond': ['$isHealthy', 1, 0]}}," +
                    "'avgFailureCount': {'$avg': '$failureCount'}" +
                    "}}"
    })
    Optional<SourceOverallStatistics> getOverallStatistics();

    @Aggregation(pipeline = {
            "{'$match': {'enabled': true}}",
            "{'$group': {" +
                    "'_id': '$priority'," +
                    "'count': {'$sum': 1}," +
                    "'healthyCount': {'$sum': {'$cond': ['$isHealthy', 1, 0]}}" +
                    "}}",
            "{'$sort': {'_id': 1}}"
    })
    List<PriorityStatistics> getPriorityStatistics();

    // Health check scheduling
    @Query("{'healthCheckIntervalSeconds': {'$exists': true}, " +
            "'$expr': {'$lt': [{'$add': ['$lastHealthCheck', {'$multiply': ['$healthCheckIntervalSeconds', 1000]}]}, ?0]}}")
    List<MarketDataSourceEntity> findSourcesDueForHealthCheck(LocalDateTime now);

    @Query("{'lastHealthCheck': {'$lt': ?0}}")
    List<MarketDataSourceEntity> findSourcesWithStaleHealthCheck(LocalDateTime cutoff);

    // Bulk operations
    @Query("{'sourceId': {'$in': ?0}}")
    List<MarketDataSourceEntity> findBySourceIdIn(List<String> sourceIds);

    @Query("{'priority': {'$in': ?0}, 'enabled': true}")
    List<MarketDataSourceEntity> findByPriorityInAndEnabledTrue(List<Integer> priorities);

    // Result interfaces for aggregations
    interface SourceOverallStatistics {
        Long getTotalSources();

        Long getEnabledSources();

        Long getHealthySources();

        Double getAvgFailureCount();
    }

    interface PriorityStatistics {
        Integer getId(); // priority level

        Long getCount();

        Long getHealthyCount();
    }
}
