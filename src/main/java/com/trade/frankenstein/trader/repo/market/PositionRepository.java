package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.PositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<PositionEntity, Long> {

    List<PositionEntity> findByOpenTrue();

    Optional<PositionEntity> findByContract_Symbol(String contractSymbol);
}
