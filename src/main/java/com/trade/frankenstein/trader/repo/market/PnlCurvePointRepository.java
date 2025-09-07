package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.PnlCurvePointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PnlCurvePointRepository extends JpaRepository<PnlCurvePointEntity, Long> {

    List<PnlCurvePointEntity> findByDayKeyOrderByAsOfAsc(String dayKey);

    Optional<PnlCurvePointEntity> findTopByOrderByAsOfDesc();
}
