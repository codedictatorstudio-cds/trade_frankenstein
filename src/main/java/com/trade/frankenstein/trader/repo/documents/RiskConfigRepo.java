package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.RiskConfig;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RiskConfigRepo extends MongoRepository<RiskConfig, String> {
}

