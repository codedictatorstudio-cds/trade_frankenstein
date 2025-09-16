package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.OptionInstrument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OptionInstrumentRepo extends MongoRepository<OptionInstrument, String> {
}

