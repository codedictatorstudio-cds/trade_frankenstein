package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.RiskConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RiskConfigRepository extends JpaRepository<RiskConfigEntity, Long> {

    Optional<RiskConfigEntity> findTopByOrderByUpdatedAtDesc();
}
