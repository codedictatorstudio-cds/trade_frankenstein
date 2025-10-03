package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.RiskEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface RiskEventRepo extends MongoRepository<RiskEvent, String> {
    
    List<RiskEvent> findTopNByOrderByTsDesc(int lastN);

    List<RiskEvent> findByTsBetween(Instant start, Instant end);
}

