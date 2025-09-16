package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RiskSnapshotRepo extends MongoRepository<RiskSnapshot, String> {
}

