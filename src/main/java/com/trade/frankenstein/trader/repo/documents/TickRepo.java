package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.Tick;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TickRepo extends MongoRepository<Tick, String> {

    /**
     * Latest tick for a symbol (now).
     */
    Optional<Tick> findTopBySymbolOrderByTsDesc(String symbol);

    /**
     * Latest tick at or before a given time (as-of lookup).
     */
    Optional<Tick> findTopBySymbolAndTsLessThanEqualOrderByTsDesc(String symbol, Instant asOf);

    /**
     * Time-ordered ticks in a window (inclusive start, exclusive end semantics per Mongo).
     */
    List<Tick> findBySymbolAndTsBetweenOrderByTsAsc(String symbol, Instant from, Instant to);

    /**
     * Multi-symbol scan within a window, ordered by symbol then time (useful for small symbol sets).
     */
    List<Tick> findBySymbolInAndTsBetweenOrderBySymbolAscTsAsc(List<String> symbols, Instant from, Instant to);

    /**
     * Fast counters for health/metrics.
     */
    long countBySymbolAndTsBetween(String symbol, Instant from, Instant to);

    /**
     * Maintenance helper (e.g., purge a backfill range before re-ingest).
     */
    long deleteBySymbolAndTsBetween(String symbol, Instant from, Instant to);
}
