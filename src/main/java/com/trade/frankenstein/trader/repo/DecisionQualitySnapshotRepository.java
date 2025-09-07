package com.trade.frankenstein.trader.repo;

import com.trade.frankenstein.trader.model.entity.DecisionQualitySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DecisionQualitySnapshotRepository extends JpaRepository<DecisionQualitySnapshotEntity, Long> {
}

