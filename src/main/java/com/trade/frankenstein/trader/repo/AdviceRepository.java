package com.trade.frankenstein.trader.repo;

import com.trade.frankenstein.trader.model.entity.AdviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdviceRepository extends JpaRepository<AdviceEntity, Long> {
}