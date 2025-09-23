package com.trade.frankenstein.trader.repo.documents;

import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.model.documents.Advice;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdviceRepo extends MongoRepository<Advice, String> {

    List<Advice> findByStatusOrderByCreatedAtDesc(AdviceStatus adviceStatus);
}