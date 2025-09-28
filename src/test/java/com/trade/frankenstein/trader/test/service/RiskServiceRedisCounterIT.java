package com.trade.frankenstein.trader.test.service;

import com.trade.frankenstein.trader.service.FlagsService;
import com.trade.frankenstein.trader.service.RiskService;
import com.trade.frankenstein.trader.service.StreamGateway;
import com.trade.frankenstein.trader.service.UpstoxService;
import com.trade.frankenstein.trader.test.BaseContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class RiskServiceRedisCounterIT extends BaseContainers {

    @Autowired
    FastStateStore fast;
    @Autowired
    UpstoxService upstox;
    @Autowired
    StreamGateway stream;
    @Autowired
    FlagsService flags;// your bean should be Redis-backed in test profile

    @Test
    void noteOrderPlacedIncrementsRollingCounter() {

        RiskService risk = new RiskService(upstox, stream, fast,flags);
        risk.noteOrderPlaced();
        Optional<String> v = fast.get("orders_per_min");
        assertThat(v).isPresent();
        long count = Long.parseLong(v.get());
        assertThat(count).isGreaterThanOrEqualTo(1L);
    }
}
