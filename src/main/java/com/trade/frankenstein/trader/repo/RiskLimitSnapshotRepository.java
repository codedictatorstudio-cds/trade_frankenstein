package com.trade.frankenstein.trader.repo;

import com.trade.frankenstein.trader.model.entity.RiskLimitSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RiskLimitSnapshotRepository extends JpaRepository<RiskLimitSnapshotEntity, Long> {
}

