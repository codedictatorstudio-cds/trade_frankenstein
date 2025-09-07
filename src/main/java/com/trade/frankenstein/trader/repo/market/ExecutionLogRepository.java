package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.ExecutionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ExecutionLogRepository extends JpaRepository<ExecutionLogEntity, Long> {

    List<ExecutionLogEntity> findByCorrelationIdOrderByTsAsc(String correlationId);

    List<ExecutionLogEntity> findByTsBetweenOrderByTsAsc(Instant from, Instant to);
}
