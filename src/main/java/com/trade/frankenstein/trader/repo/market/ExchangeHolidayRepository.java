package com.trade.frankenstein.trader.repo.market;

import com.trade.frankenstein.trader.model.entity.market.ExchangeHolidayEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExchangeHolidayRepository extends JpaRepository<ExchangeHolidayEntity, Long> {
}
