package com.trade.frankenstein.trader.repo;

import com.trade.frankenstein.trader.model.entity.TradeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TradeRepository extends JpaRepository<TradeEntity, Long> {

    boolean existsByBrokerTradeId(String brokerTradeId);

    // Optional if you expose publicId externally:
    Optional<TradeEntity> findByPublicId(String publicId);
}


