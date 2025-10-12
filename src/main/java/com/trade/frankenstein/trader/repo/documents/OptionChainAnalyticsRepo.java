package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.OptionChainAnalytics;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface OptionChainAnalyticsRepo extends MongoRepository<OptionChainAnalytics, String> {

    Optional<OptionChainAnalytics> findTopByUnderlyingKeyAndExpiryOrderByCalculatedAtDesc(
            String underlyingKey, LocalDate expiry);

    List<OptionChainAnalytics> findByUnderlyingKeyAndExpiryAndCalculatedAtAfterOrderByCalculatedAtDesc(
            String underlyingKey, LocalDate expiry, Instant after);

    @Query("{'underlyingKey': ?0, 'calculatedAt': {$gte: ?1}}")
    List<OptionChainAnalytics> findHistoricalAnalytics(String underlyingKey, Instant since);

    void deleteByCalculatedAtBefore(Instant cutoff);

    Page<OptionChainAnalytics> findByUnderlyingKeyOrderByCalculatedAtDesc(
            String underlyingKey, Pageable pageable);
}
