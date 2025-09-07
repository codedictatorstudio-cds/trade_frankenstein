package com.trade.frankenstein.trader.repo;

import com.trade.frankenstein.trader.model.entity.InstrumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InstrumentRepository extends JpaRepository<InstrumentEntity, Long> {
}

