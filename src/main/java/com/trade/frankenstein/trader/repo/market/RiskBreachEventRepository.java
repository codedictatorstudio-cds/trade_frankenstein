package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.RiskBreachEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface RiskBreachEventRepository extends JpaRepository<RiskBreachEventEntity, Long> {

    List<RiskBreachEventEntity> findByCorrelationIdOrderByAsOfAsc(String correlationId);

    List<RiskBreachEventEntity> findByAsOfBetweenOrderByAsOfDesc(Instant from, Instant to);
}
