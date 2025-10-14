package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.OutboxEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepo extends MongoRepository<OutboxEvent, String> {
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();
}
