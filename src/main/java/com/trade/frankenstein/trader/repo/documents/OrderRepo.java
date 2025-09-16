package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.model.documents.Order;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderRepo extends MongoRepository<Order, String> {
}
