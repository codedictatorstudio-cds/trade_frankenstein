package com.trade.frankenstein.trader.config;

import com.trade.frankenstein.trader.service.decision.PercentileWindow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    @Qualifier("decisionLatencyP95Window")
    public PercentileWindow decisionLatencyP95Window() {
        return new PercentileWindow(2048);
    }
}
