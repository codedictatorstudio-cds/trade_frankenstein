package com.trade.frankenstein.trader.repo;

import com.trade.frankenstein.trader.model.entity.MarketSentimentSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketSentimentSnapshotRepository extends JpaRepository<MarketSentimentSnapshotEntity, Long> {
}

