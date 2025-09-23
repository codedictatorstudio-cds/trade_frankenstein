// Ta4jConfig.java
package com.trade.frankenstein.trader.config;

import com.trade.frankenstein.trader.common.Ta4jIndicators;
import com.trade.frankenstein.trader.common.constants.BacktestProperties;
import com.trade.frankenstein.trader.common.constants.Ta4jProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({Ta4jProperties.class, BacktestProperties.class})
public class Ta4jConfig {

    @Bean
    public Ta4jIndicators ta4jIndicators(
            Ta4jProperties props) {
        return new Ta4jIndicators(
                props.getEmaFast(),
                props.getEmaSlow(),
                props.getAdxPeriod(),
                props.getAtrPeriod(),
                props.getDonchianWindow(),
                props.getVwapLookback()
        );
    }

}
