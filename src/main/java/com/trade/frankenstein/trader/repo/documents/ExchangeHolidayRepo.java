package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.ExchangeHoliday;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExchangeHolidayRepo extends MongoRepository<ExchangeHoliday, String> {
}

