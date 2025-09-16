package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.ExecutionLog;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExecutionLogRepo extends MongoRepository<ExecutionLog, String> {
}

