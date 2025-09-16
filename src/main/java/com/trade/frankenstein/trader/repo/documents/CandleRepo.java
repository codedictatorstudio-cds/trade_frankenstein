package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.Candle;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CandleRepo extends MongoRepository<Candle, String> {
}

