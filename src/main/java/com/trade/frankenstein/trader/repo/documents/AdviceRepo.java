package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.enums.ExecutionContext;
import com.trade.frankenstein.trader.enums.RiskCategory;
import com.trade.frankenstein.trader.enums.StrategyName;
import com.trade.frankenstein.trader.model.documents.Advice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Enhanced MongoDB repository for Advice entities with comprehensive
 * query methods for auto trading bot operations.
 */
@Repository
public interface AdviceRepo extends MongoRepository<Advice, String> {

    // Existing method
    List<Advice> findByStatusOrderByCreatedAtDesc(AdviceStatus adviceStatus);

    // Status-based queries
    List<Advice> findByStatusAndCreatedAtBefore(AdviceStatus status, Instant cutoff);

    List<Advice> findByStatusAndExpiresAtBefore(AdviceStatus status, Instant expiry);

    List<Advice> findByStatusInOrderByPriorityScoreDescCreatedAtDesc(List<AdviceStatus> statuses);

    // Instrument-based queries
    List<Advice> findByInstrumentTokenAndStatus(String instrumentToken, AdviceStatus status);

    List<Advice> findByInstrumentTokenAndStatusIn(String instrumentToken, List<AdviceStatus> statuses);

    List<Advice> findByInstrumentTokenAndTransactionType(String instrumentToken, String transactionType);

    // Strategy-based queries
    List<Advice> findByStrategyAndStatus(StrategyName strategy, AdviceStatus status);

    List<Advice> findByStrategyAndStatusInOrderByCreatedAtDesc(StrategyName strategy, List<AdviceStatus> statuses);

    List<Advice> findByStrategyInAndStatus(List<StrategyName> strategies, AdviceStatus status);

    // Priority and execution context queries
    @Query("{'status': ?0, 'priorityScore': {$gte: ?1}}")
    List<Advice> findByStatusAndMinPriority(AdviceStatus status, Integer minPriority);

    List<Advice> findByExecutionContextAndStatus(ExecutionContext context, AdviceStatus status);

    List<Advice> findByExecutionContextInAndStatusIn(List<ExecutionContext> contexts, List<AdviceStatus> statuses);

    // Risk-based queries
    List<Advice> findByRiskCategoryAndStatus(RiskCategory riskCategory, AdviceStatus status);

    List<Advice> findByRiskCategoryInAndStatusIn(List<RiskCategory> categories, List<AdviceStatus> statuses);

    @Query("{'riskCategory': {$in: ['HIGH', 'CRITICAL']}, 'status': 'PENDING'}")
    List<Advice> findHighRiskPendingAdvices();

    // Time-based queries
    List<Advice> findByCreatedAtBetween(Instant start, Instant end);

    List<Advice> findByCreatedAtAfterAndStatus(Instant after, AdviceStatus status);

    @Query("{'createdAt': {$gte: ?0}, 'status': {$in: ?1}}")
    List<Advice> findRecentAdvicesByStatus(Instant since, List<AdviceStatus> statuses);

    // Expiry management
    @Query("{'expiresAt': {$lte: ?0}, 'status': {$in: ['PENDING', 'VALIDATED', 'QUEUED']}}")
    List<Advice> findExpiredPendingAdvices(Instant now);

    @Query("{'expiresAt': {$lte: ?0, $gte: ?1}}")
    List<Advice> findAdvicesExpiringBetween(Instant end, Instant start);

    // Performance and analytics queries
    @Query("{'status': 'COMPLETED', 'realizedPnl': {$ne: null}}")
    List<Advice> findCompletedAdvicesWithPnl();

    @Query("{'strategy': ?0, 'wasSuccessful': ?1, 'createdAt': {$gte: ?2}}")
    List<Advice> findStrategyPerformance(StrategyName strategy, Boolean successful, Instant since);

    // Parent-child relationship queries
    List<Advice> findByParentAdviceId(String parentId);

    @Query("{'parentAdviceId': {$exists: false}, 'status': {$in: ?0}}")
    List<Advice> findRootAdvicesByStatus(List<AdviceStatus> statuses);

    // Error and retry management
    @Query("{'status': 'FAILED', 'retryCount': {$lt: 3}}")
    List<Advice> findRetryableFailedAdvices();

    List<Advice> findByLastErrorIsNotNullAndStatus(AdviceStatus status);

    // Portfolio and position queries
    List<Advice> findByPositionTypeAndStatus(String positionType, AdviceStatus status);

    @Query("{'positionType': 'ENTRY', 'status': 'EXECUTED', 'instrument_token': ?0}")
    List<Advice> findExecutedEntryAdvicesForInstrument(String instrumentToken);

    // Aggregation queries for analytics
    @Aggregation(pipeline = {
            "{ '$match': { 'status': 'COMPLETED', 'createdAt': { '$gte': ?0 } } }",
            "{ '$group': { '_id': '$strategy', 'count': { '$sum': 1 }, 'avgPnl': { '$avg': '$realizedPnl' } } }",
            "{ '$sort': { 'count': -1 } }"
    })
    List<StrategyPerformanceDto> getStrategyPerformanceStats(Instant since);

    @Aggregation(pipeline = {
            "{ '$match': { 'status': { '$in': ['EXECUTED', 'COMPLETED'] } } }",
            "{ '$group': { '_id': '$executionContext', 'count': { '$sum': 1 }, 'successRate': { '$avg': { '$cond': ['$wasSuccessful', 1, 0] } } } }"
    })
    List<ExecutionContextStatsDto> getExecutionContextStats();

    // Custom complex queries
    @Query("{'status': {$in: ['PENDING', 'VALIDATED']}, " +
            "'priorityScore': {$gte: ?0}, " +
            "'riskCategory': {$ne: 'CRITICAL'}, " +
            "'expiresAt': {$gt: ?1}}")
    List<Advice> findExecutableAdvices(Integer minPriority, Instant now);

    @Query("{'instrument_token': ?0, " +
            "'status': {$in: ['EXECUTED', 'PARTIALLY_FILLED']}, " +
            "'transaction_type': 'BUY'}")
    List<Advice> findOpenPositionsForInstrument(String instrumentToken);

    // Count queries for monitoring
    long countByStatusAndCreatedAtAfter(AdviceStatus status, Instant after);

    long countByRiskCategoryAndStatus(RiskCategory category, AdviceStatus status);

    long countByStrategyAndStatusAndCreatedAtAfter(StrategyName strategy, AdviceStatus status, Instant after);

    // Page-based queries for UI
    Page<Advice> findByStatusInOrderByPriorityScoreDescCreatedAtDesc(List<AdviceStatus> statuses, Pageable pageable);

    Page<Advice> findByCreatedAtAfterOrderByCreatedAtDesc(Instant after, Pageable pageable);

    // DTOs for aggregation results
    interface StrategyPerformanceDto {
        String getStrategy();

        Long getCount();

        Double getAvgPnl();
    }

    interface ExecutionContextStatsDto {
        String getExecutionContext();

        Long getCount();

        Double getSuccessRate();
    }

    @Query("{ 'strategy': { $exists: true }, 'status': 'PENDING' }")
    List<Advice> findAllPendingByStrategy();
}
