package com.trade.frankenstein.trader.repo.upstox;

import com.trade.frankenstein.trader.model.upstox.AuthenticationResponse;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthenticationResponseRepo extends MongoRepository<AuthenticationResponse, String> {
}
