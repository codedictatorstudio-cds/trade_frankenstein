package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.TickEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TickRepository extends JpaRepository<TickEntity, Long> {

    Optional<TickEntity> findTopBySymbolOrderByTsDesc(String symbol);

    List<TickEntity> findBySymbolAndTsBetweenOrderByTsAsc(String symbol, Instant from, Instant to);
}
