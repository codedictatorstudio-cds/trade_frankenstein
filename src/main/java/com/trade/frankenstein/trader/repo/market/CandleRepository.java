package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.CandleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface CandleRepository extends JpaRepository<CandleEntity, Long> {

    List<CandleEntity> findBySymbolAndOpenTimeBetweenOrderByOpenTimeAsc(String symbol, Instant from, Instant to);

    Optional<CandleEntity> findTopBySymbolOrderByOpenTimeDesc(String symbol);

    Optional<CandleEntity> findBySymbolAndOpenTime(String symbol, Instant openTime);

    void deleteBySymbolAndOpenTimeBetween(String symbol, Instant from, Instant to);
}
