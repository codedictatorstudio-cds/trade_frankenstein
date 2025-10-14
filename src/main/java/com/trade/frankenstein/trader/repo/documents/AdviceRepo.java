package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.model.documents.Advice;
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

    List<Advice> findByStatusInOrderByPriorityScoreDescCreatedAtDesc(List<AdviceStatus> statuses);

    // Expiry management
    @Query("{'expiresAt': {$lte: ?0}, 'status': {$in: ['PENDING', 'VALIDATED', 'QUEUED']}}")
    List<Advice> findExpiredPendingAdvices(Instant now);

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
