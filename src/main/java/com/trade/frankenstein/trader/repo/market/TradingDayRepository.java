package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.TradingDayEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TradingDayRepository extends JpaRepository<TradingDayEntity, Long> {

    List<TradingDayEntity> findByTradeDateBetween(LocalDate from, LocalDate to);

}
