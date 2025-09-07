package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.BrokerSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BrokerSessionRepository extends JpaRepository<BrokerSessionEntity, Long> {

    Optional<BrokerSessionEntity> findTopByBrokerOrderByAccessTokenExpiresAtDesc(String broker);
}
