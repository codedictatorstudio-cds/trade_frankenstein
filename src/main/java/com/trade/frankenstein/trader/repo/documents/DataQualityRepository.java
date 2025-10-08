package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.DataQualityEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DataQualityRepository extends MongoRepository<DataQualityEventEntity, String> {

    // Basic queries
    List<DataQualityEventEntity> findByInstrumentKeyAndEventTypeOrderByTimestampDesc(
            String instrumentKey, String eventType);

    List<DataQualityEventEntity> findByInstrumentKeyOrderByTimestampDesc(String instrumentKey, Pageable pageable);

    List<DataQualityEventEntity> findByEventTypeAndTimestampAfterOrderByTimestampDesc(
            String eventType, LocalDateTime since);

    // Severity-based queries
    @Query("{'severity': ?0, 'timestamp': {'$gte': ?1}}")
    List<DataQualityEventEntity> findBySeveritySince(String severity, LocalDateTime since);

    @Query("{'severity': {'$in': ['HIGH', 'CRITICAL']}, 'isResolved': false}")
    List<DataQualityEventEntity> findCriticalUnresolvedEvents(Pageable pageable);

    // Resolution status queries
    @Query("{'isResolved': false}")
    List<DataQualityEventEntity> findUnresolvedEvents(Pageable pageable);

    @Query("{'instrumentKey': ?0, 'isResolved': false}")
    List<DataQualityEventEntity> findUnresolvedEventsForInstrument(String instrumentKey);

    @Query("{'isResolved': true, 'resolvedAt': {'$gte': ?0}}")
    List<DataQualityEventEntity> findRecentlyResolvedEvents(LocalDateTime since);

    // Source-based queries
    List<DataQualityEventEntity> findBySourceOrderByTimestampDesc(String source, Pageable pageable);

    @Query("{'source': ?0, 'timestamp': {'$gte': ?1}}")
    List<DataQualityEventEntity> findBySourceSince(String source, LocalDateTime since);

    // Statistical aggregations
    @Aggregation(pipeline = {
            "{'$match': {'timestamp': {'$gte': ?0}}}",
            "{'$group': {" +
                    "'_id': '$eventType'," +
                    "'count': {'$sum': 1}," +
                    "'unresolvedCount': {'$sum': {'$cond': [{'$eq': ['$isResolved', false]}, 1, 0]}}," +
                    "'criticalCount': {'$sum': {'$cond': [{'$in': ['$severity', ['HIGH', 'CRITICAL']]}, 1, 0]}}" +
                    "}}",
            "{'$sort': {'count': -1}}"
    })
    List<EventTypeStatistics> getEventTypeStatistics(LocalDateTime since);

    @Aggregation(pipeline = {
            "{'$match': {'timestamp': {'$gte': ?0}}}",
            "{'$group': {" +
                    "'_id': '$instrumentKey'," +
                    "'eventCount': {'$sum': 1}," +
                    "'unresolvedCount': {'$sum': {'$cond': [{'$eq': ['$isResolved', false]}, 1, 0]}}," +
                    "'avgAffectedDataPoints': {'$avg': '$affectedDataPoints'}" +
                    "}}",
            "{'$sort': {'eventCount': -1}}"
    })
    List<InstrumentQualityStatistics> getInstrumentQualityStatistics(LocalDateTime since);

    @Aggregation(pipeline = {
            "{'$match': {'timestamp': {'$gte': ?0}}}",
            "{'$group': {" +
                    "'_id': '$source'," +
                    "'eventCount': {'$sum': 1}," +
                    "'severityBreakdown': {" +
                    "'$push': {'severity': '$severity', 'count': 1}" +
                    "}" +
                    "}}",
            "{'$sort': {'eventCount': -1}}"
    })
    List<SourceQualityStatistics> getSourceQualityStatistics(LocalDateTime since);

    // Time-based analysis
    @Aggregation(pipeline = {
            "{'$match': {'timestamp': {'$gte': ?0}}}",
            "{'$addFields': {" +
                    "'hour': {'$hour': '$timestamp'}," +
                    "'dayOfWeek': {'$dayOfWeek': '$timestamp'}" +
                    "}}",
            "{'$group': {" +
                    "'_id': {'hour': '$hour', 'dayOfWeek': '$dayOfWeek'}," +
                    "'eventCount': {'$sum': 1}," +
                    "'criticalCount': {'$sum': {'$cond': [{'$eq': ['$severity', 'CRITICAL']}, 1, 0]}}" +
                    "}}",
            "{'$sort': {'_id.dayOfWeek': 1, '_id.hour': 1}}"
    })
    List<TimeBasedEventStatistics> getTimeBasedEventStatistics(LocalDateTime since);

    // Resolution performance
    @Aggregation(pipeline = {
            "{'$match': {'isResolved': true, 'resolvedAt': {'$gte': ?0}}}",
            "{'$addFields': {" +
                    "'resolutionTimeMinutes': {" +
                    "'$divide': [{'$subtract': ['$resolvedAt', '$timestamp']}, 60000]" +
                    "}" +
                    "}}",
            "{'$group': {" +
                    "'_id': '$eventType'," +
                    "'avgResolutionTime': {'$avg': '$resolutionTimeMinutes'}," +
                    "'count': {'$sum': 1}" +
                    "}}",
            "{'$sort': {'avgResolutionTime': 1}}"
    })
    List<ResolutionPerformance> getResolutionPerformance(LocalDateTime since);

    // Trend analysis
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timestamp': {'$gte': ?1}}}",
            "{'$addFields': {" +
                    "'dateOnly': {'$dateToString': {'format': '%Y-%m-%d', 'date': '$timestamp'}}" +
                    "}}",
            "{'$group': {" +
                    "'_id': '$dateOnly'," +
                    "'eventCount': {'$sum': 1}," +
                    "'severityScore': {'$avg': {" +
                    "'$switch': {" +
                    "'branches': [" +
                    "{'case': {'$eq': ['$severity', 'LOW']}, 'then': 1}," +
                    "{'case': {'$eq': ['$severity', 'MEDIUM']}, 'then': 2}," +
                    "{'case': {'$eq': ['$severity', 'HIGH']}, 'then': 3}," +
                    "{'case': {'$eq': ['$severity', 'CRITICAL']}, 'then': 4}" +
                    "]," +
                    "'default': 0" +
                    "}" +
                    "}}" +
                    "}}",
            "{'$sort': {'_id': 1}}"
    })
    List<DailyQualityTrend> getDailyQualityTrend(String instrumentKey, LocalDateTime since);

    // Cleanup operations
    void deleteByTimestampBeforeAndIsResolvedTrue(LocalDateTime cutoff);

    @Query(value = "{'timestamp': {'$lt': ?0}, 'severity': 'LOW', 'isResolved': true}", delete = true)
    void deleteLowSeverityResolvedEventsOlderThan(LocalDateTime cutoff);

    // Count queries
    @Query(value = "{'instrumentKey': ?0, 'isResolved': false}", count = true)
    Long countUnresolvedEventsForInstrument(String instrumentKey);

    @Query(value = "{'severity': {'$in': ['HIGH', 'CRITICAL']}, 'isResolved': false}", count = true)
    Long countCriticalUnresolvedEvents();

    @Query(value = "{'timestamp': {'$gte': ?0}}", count = true)
    Long countEventsSince(LocalDateTime since);

    // Result interfaces for aggregations
    interface EventTypeStatistics {
        String getId(); // eventType

        Long getCount();

        Long getUnresolvedCount();

        Long getCriticalCount();
    }

    interface InstrumentQualityStatistics {
        String getId(); // instrumentKey

        Long getEventCount();

        Long getUnresolvedCount();

        Double getAvgAffectedDataPoints();
    }

    interface SourceQualityStatistics {
        String getId(); // source

        Long getEventCount();

        List<Object> getSeverityBreakdown();
    }

    interface TimeBasedEventStatistics {
        TimeBasedId getId();

        Long getEventCount();

        Long getCriticalCount();

        interface TimeBasedId {
            Integer getHour();

            Integer getDayOfWeek();
        }
    }

    interface ResolutionPerformance {
        String getId(); // eventType

        Double getAvgResolutionTime();

        Long getCount();
    }

    interface DailyQualityTrend {
        String getId(); // date string

        Long getEventCount();

        Double getSeverityScore();
    }
}
