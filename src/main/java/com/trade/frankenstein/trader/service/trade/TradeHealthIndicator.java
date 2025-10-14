package com.trade.frankenstein.trader.service.trade;

import com.trade.frankenstein.trader.repo.documents.TradeRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class TradeHealthIndicator implements HealthIndicator {

    @Autowired
    private TradeRepo tradeRepo;

    @Override
    public Health health() {
        try {
            long tradeCount = tradeRepo.count();

            if (tradeCount >= 0) {
                return Health.up()
                        .withDetail("trade_count", tradeCount)
                        .withDetail("status", "Trade repository accessible")
                        .build();
            } else {
                return Health.down()
                        .withDetail("status", "Trade repository returned negative count")
                        .build();
            }
        } catch (Exception e) {
            return Health.down()
                    .withDetail("status", "Trade repository not accessible")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
