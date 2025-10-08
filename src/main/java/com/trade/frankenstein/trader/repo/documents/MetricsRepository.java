package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.MetricsEntity;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricsRepository extends MongoRepository<MetricsEntity, String> {

    List<MetricsEntity> findByMetricNameAndTimestampBetweenOrderByTimestampDesc(
            String metricName, LocalDateTime start, LocalDateTime end);

    List<MetricsEntity> findByMetricNameOrderByTimestampDesc(String metricName);

    List<MetricsEntity> findByMetricTypeAndTimestampAfterOrderByTimestampDesc(
            String metricType, LocalDateTime since);

    void deleteByTimestampBefore(LocalDateTime cutoff);

    @Query("{'metricName': {'$regex': ?0}, 'timestamp': {'$gte': ?1}}")
    List<MetricsEntity> findByMetricNamePatternAndTimestampAfter(String pattern, LocalDateTime since);

    @Aggregation(pipeline = {
            "{'$match': {'metricName': ?0, 'timestamp': {'$gte': ?1}}}",
            "{'$group': {'_id': null, 'avgValue': {'$avg': '$value'}, 'count': {'$sum': 1}}}"
    })
    MetricAggregateResult getAverageValue(String metricName, LocalDateTime since);

    interface MetricAggregateResult {
        Double getAvgValue();

        Long getCount();
    }
}
