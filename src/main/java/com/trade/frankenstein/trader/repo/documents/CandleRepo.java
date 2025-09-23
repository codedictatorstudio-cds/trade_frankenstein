package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.Candle;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CandleRepo extends MongoRepository<Candle, String> {

    /**
     * Latest candle for a symbol (most recent 1m bar).
     */
    Optional<Candle> findTopBySymbolOrderByOpenTimeDesc(String symbol);

    /**
     * As-of lookup: latest candle at or before a given time.
     */
    Optional<Candle> findTopBySymbolAndOpenTimeLessThanEqualOrderByOpenTimeDesc(String symbol, Instant asOf);

    /**
     * Time-ordered candles in a window (inclusive).
     */
    List<Candle> findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(String symbol, Instant from, Instant to);

    /**
     * Multi-symbol scan within a window, ordered by symbol then time.
     */
    List<Candle> findBySymbolInAndOpenTimeBetweenOrderBySymbolAscOpenTimeAsc(List<String> symbols, Instant from, Instant to);

    /**
     * Counts for health/metrics.
     */
    long countBySymbolAndOpenTimeBetween(String symbol, Instant from, Instant to);

    /**
     * Maintenance helper (e.g., clear a backfill window before re-ingest).
     */
    long deleteBySymbolAndOpenTimeBetween(String symbol, Instant from, Instant to);
}
