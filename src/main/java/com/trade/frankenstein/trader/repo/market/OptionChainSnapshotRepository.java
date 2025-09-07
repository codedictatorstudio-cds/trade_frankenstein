package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.OptionChainSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface OptionChainSnapshotRepository extends JpaRepository<OptionChainSnapshotEntity, Long> {

    List<OptionChainSnapshotEntity> findTop500ByUnderlyingSymbolOrderByAsOfDesc(String underlying);

    List<OptionChainSnapshotEntity> findByUnderlyingSymbolAndExpiryAndAsOfBetweenOrderByStrikeAsc(String u, LocalDate e, Instant from, Instant to);
}
