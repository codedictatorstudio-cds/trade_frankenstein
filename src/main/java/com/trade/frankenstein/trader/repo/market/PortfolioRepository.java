package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.PortfolioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PortfolioRepository extends JpaRepository<PortfolioEntity, Long> {

    Optional<PortfolioEntity> findTopByOrderByUpdatedAtDesc();
}
