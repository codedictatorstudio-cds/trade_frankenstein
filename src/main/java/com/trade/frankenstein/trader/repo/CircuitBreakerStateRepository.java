package com.trade.frankenstein.trader.repo;

import com.trade.frankenstein.trader.model.entity.CircuitBreakerStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CircuitBreakerStateRepository extends JpaRepository<CircuitBreakerStateEntity, Long> {
}

