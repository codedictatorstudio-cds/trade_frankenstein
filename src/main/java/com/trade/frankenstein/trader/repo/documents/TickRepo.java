package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.Tick;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TickRepo extends MongoRepository<Tick, String> {

    Optional<Tick> findTopBySymbolOrderByTsDesc(String symbol);
}

