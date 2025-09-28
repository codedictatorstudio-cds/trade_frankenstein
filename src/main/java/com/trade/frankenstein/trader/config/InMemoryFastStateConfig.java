package com.trade.frankenstein.trader.config;

import com.trade.frankenstein.trader.core.FastStateStore;
import com.trade.frankenstein.trader.core.InMemoryFastStateStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "trade.redis.enabled", havingValue = "false", matchIfMissing = false)
public class InMemoryFastStateConfig {

    @Bean
    public FastStateStore fastStateStore() {
        return new InMemoryFastStateStore("tf:");
    }
}
