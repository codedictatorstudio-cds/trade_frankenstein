package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.GreeksSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface GreeksSnapshotRepo extends MongoRepository<GreeksSnapshot, String> {

    List<GreeksSnapshot> findByInstrumentKeyAndTimestampAfterOrderByTimestampDesc(
            String instrumentKey, Instant after);

    List<GreeksSnapshot> findByInstrumentKeyOrderByTimestampDesc(String instrumentKey);

    @Query("{'underlyingKey': ?0, 'expiry': ?1, 'timestamp': {$gte: ?2, $lte: ?3}}")
    List<GreeksSnapshot> findByUnderlyingAndExpiryBetweenTimestamps(
            String underlyingKey, LocalDate expiry, Instant start, Instant end);

    void deleteByTimestampBefore(Instant cutoff);
}
