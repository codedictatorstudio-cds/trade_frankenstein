package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.enums.TradeStatus;
import com.trade.frankenstein.trader.model.documents.Trade;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradeRepo extends MongoRepository<Trade, String> {

    Optional<Trade> findByBrokerTradeId(String brokerTradeId);

    List<Trade> findTopNBySymbolAndStatusOrderByExitTimeDesc(String nifty, TradeStatus tradeStatus, int window);
}

