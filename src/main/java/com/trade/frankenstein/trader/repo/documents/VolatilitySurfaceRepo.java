package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.VolatilitySurface;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VolatilitySurfaceRepo extends MongoRepository<VolatilitySurface, String> {

    Optional<VolatilitySurface> findTopByUnderlyingKeyAndExpiryOrderByTimestampDesc(
            String underlyingKey, LocalDate expiry);

    List<VolatilitySurface> findByUnderlyingKeyAndTimestampAfterOrderByTimestampDesc(
            String underlyingKey, Instant after);

    List<VolatilitySurface> findByUnderlyingKeyAndExpiryAndTimestampBetween(
            String underlyingKey, LocalDate expiry, Instant start, Instant end);

    void deleteByTimestampBefore(Instant cutoff);
}
