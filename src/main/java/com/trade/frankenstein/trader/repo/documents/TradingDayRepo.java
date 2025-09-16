package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.TradingDay;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TradingDayRepo extends MongoRepository<TradingDay, String> {
}


