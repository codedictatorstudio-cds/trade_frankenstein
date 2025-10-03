package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.RiskSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface RiskSnapshotRepo extends MongoRepository<RiskSnapshot, String> {
    
    List<RiskSnapshot> findByAsOfBetween(Instant from, Instant to);

    List<RiskSnapshot> findTopNByOrderByAsOfDesc(int lastN);
}

