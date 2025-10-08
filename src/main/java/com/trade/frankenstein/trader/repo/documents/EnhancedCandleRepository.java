package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.CandleEntity;
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
public interface EnhancedCandleRepository extends MongoRepository<CandleEntity, String> {

    // Basic queries
    List<CandleEntity> findByInstrumentKeyAndTimeframeAndTimestampBetweenOrderByTimestampAsc(
            String instrumentKey, String timeframe, LocalDateTime start, LocalDateTime end);

    List<CandleEntity> findByInstrumentKeyAndTimeframeOrderByTimestampDesc(
            String instrumentKey, String timeframe, Pageable pageable);

    Optional<CandleEntity> findTopByInstrumentKeyAndTimeframeOrderByTimestampDesc(
            String instrumentKey, String timeframe);

    // Quality and completeness queries
    @Query("{'instrumentKey': ?0, 'timeframe': ?1, 'isComplete': true}")
    List<CandleEntity> findCompleteCandles(String instrumentKey, String timeframe, Pageable pageable);

    @Query("{'hasGaps': true, 'timestamp': {'$gte': ?0}}")
    List<CandleEntity> findCandlesWithGapsSince(LocalDateTime since);

    @Query("{'qualityScore': {'$lt': ?0}}")
    List<CandleEntity> findLowQualityCandles(BigDecimal threshold);

    // Multi-timeframe queries
    @Query("{'instrumentKey': ?0, 'timeframe': {'$in': ?1}, 'timestamp': {'$gte': ?2}}")
    List<CandleEntity> findByInstrumentKeyAndTimeframesAndTimestampAfter(
            String instrumentKey, List<String> timeframes, LocalDateTime since);

    // OHLC data queries
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timeframe': ?1, 'timestamp': {'$gte': ?2}}}",
            "{'$sort': {'timestamp': 1}}",
            "{'$group': {" +
                    "'_id': null," +
                    "'firstOpen': {'$first': '$open'}," +
                    "'lastClose': {'$last': '$close'}," +
                    "'highestHigh': {'$max': '$high'}," +
                    "'lowestLow': {'$min': '$low'}," +
                    "'totalVolume': {'$sum': '$volume'}" +
                    "}}"
    })
    Optional<OHLCAggregate> getOHLCAggregate(String instrumentKey, String timeframe, LocalDateTime since);

    // Technical analysis queries
    @Query("{'instrumentKey': ?0, 'timeframe': ?1, 'technicalIndicators.rsi': {'$exists': true}}")
    List<CandleEntity> findCandlesWithRSI(String instrumentKey, String timeframe, Pageable pageable);

    @Query("{'instrumentKey': ?0, 'timeframe': ?1, 'technicalIndicators.macd': {'$exists': true}}")
    List<CandleEntity> findCandlesWithMACD(String instrumentKey, String timeframe, Pageable pageable);

    // Volume analysis
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timeframe': ?1, 'timestamp': {'$gte': ?2}}}",
            "{'$group': {" +
                    "'_id': null," +
                    "'avgVolume': {'$avg': '$volume'}," +
                    "'maxVolume': {'$max': '$volume'}," +
                    "'totalTrades': {'$sum': '$tradeCount'}" +
                    "}}"
    })
    Optional<VolumeStatistics> getVolumeStatistics(String instrumentKey, String timeframe, LocalDateTime since);

    // Price movement analysis
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timeframe': ?1, 'timestamp': {'$gte': ?2}}}",
            "{'$addFields': {" +
                    "'priceChange': {'$subtract': ['$close', '$open']}," +
                    "'priceChangePercent': {'$multiply': [{'$divide': [{'$subtract': ['$close', '$open']}, '$open']}, 100]}" +
                    "}}",
            "{'$group': {" +
                    "'_id': null," +
                    "'bullishCandles': {'$sum': {'$cond': [{'$gt': ['$priceChange', 0]}, 1, 0]}}," +
                    "'bearishCandles': {'$sum': {'$cond': [{'$lt': ['$priceChange', 0]}, 1, 0]}}," +
                    "'avgPriceChange': {'$avg': '$priceChangePercent'}," +
                    "'volatility': {'$stdDevPop': '$priceChangePercent'}" +
                    "}}"
    })
    Optional<PriceMovementStatistics> getPriceMovementStatistics(
            String instrumentKey, String timeframe, LocalDateTime since);

    // Gap analysis
    @Query("{'instrumentKey': ?0, 'timeframe': ?1, 'timestamp': {'$gte': ?2}}")
    List<CandleEntity> findCandlesForGapAnalysis(String instrumentKey, String timeframe, LocalDateTime since);

    // Cleanup and maintenance
    void deleteByTimestampBefore(LocalDateTime cutoff);

    @Query(value = "{'isComplete': false, 'timestamp': {'$lt': ?0}}", delete = true)
    void deleteIncompleteOldCandles(LocalDateTime cutoff);

    // Batch operations
    @Query("{'instrumentKey': {'$in': ?0}, 'timeframe': ?1}")
    List<CandleEntity> findLatestCandlesForInstruments(List<String> instrumentKeys, String timeframe, Pageable pageable);

    // Time-based aggregations
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timeframe': ?1, 'timestamp': {'$gte': ?2}}}",
            "{'$addFields': {" +
                    "'hour': {'$hour': '$timestamp'}," +
                    "'dayOfWeek': {'$dayOfWeek': '$timestamp'}" +
                    "}}",
            "{'$group': {" +
                    "'_id': {'hour': '$hour', 'dayOfWeek': '$dayOfWeek'}," +
                    "'avgVolume': {'$avg': '$volume'}," +
                    "'avgRange': {'$avg': {'$subtract': ['$high', '$low']}}," +
                    "'count': {'$sum': 1}" +
                    "}}",
            "{'$sort': {'_id.dayOfWeek': 1, '_id.hour': 1}}"
    })
    List<TimeBasedStatistics> getTimeBasedStatistics(String instrumentKey, String timeframe, LocalDateTime since);

    // Support and resistance levels
    @Aggregation(pipeline = {
            "{'$match': {'instrumentKey': ?0, 'timeframe': ?1, 'timestamp': {'$gte': ?2}}}",
            "{'$group': {" +
                    "'_id': null," +
                    "'resistanceLevels': {'$push': '$high'}," +
                    "'supportLevels': {'$push': '$low'}" +
                    "}}"
    })
    Optional<SupportResistanceLevels> getSupportResistanceLevels(
            String instrumentKey, String timeframe, LocalDateTime since);

    // Result interfaces for aggregations
    interface OHLCAggregate {
        BigDecimal getFirstOpen();

        BigDecimal getLastClose();

        BigDecimal getHighestHigh();

        BigDecimal getLowestLow();

        Long getTotalVolume();
    }

    interface VolumeStatistics {
        Double getAvgVolume();

        Long getMaxVolume();

        Long getTotalTrades();
    }

    interface PriceMovementStatistics {
        Long getBullishCandles();

        Long getBearishCandles();

        Double getAvgPriceChange();

        Double getVolatility();
    }

    interface TimeBasedStatistics {
        TimeBasedId getId();

        Double getAvgVolume();

        Double getAvgRange();

        Long getCount();

        interface TimeBasedId {
            Integer getHour();

            Integer getDayOfWeek();
        }
    }

    interface SupportResistanceLevels {
        List<BigDecimal> getResistanceLevels();

        List<BigDecimal> getSupportLevels();
    }
}
