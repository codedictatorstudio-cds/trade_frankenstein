package com.trade.frankenstein.trader.repo;

import com.trade.frankenstein.trader.model.entity.MarketRegimeSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketRegimeSnapshotRepository extends JpaRepository<MarketRegimeSnapshotEntity, Long> {
}

