package com.trade.frankenstein.trader.test.repo;

import com.trade.frankenstein.trader.test.BaseContainers;
import com.trade.frankenstein.trader.model.documents.Tick;
import com.trade.frankenstein.trader.repo.documents.TickRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest
@ActiveProfiles("test")
class TickRepoIT extends BaseContainers {

    @Autowired
    TickRepo tickRepo;

    @Test
    void findBySymbolAndTsBetweenOrderByTsAsc() {
        Tick t1 = Tick.builder()
                .ts(Instant.now().minusSeconds(90))
                .symbol("NIFTY")
                .quantity(1L)
                .ltp(20000.0)
                .build();
        Tick t2 = Tick.builder()
                .ts(Instant.now().minusSeconds(30))
                .symbol("NIFTY")
                .quantity(1L)
                .ltp(20010.0)
                .build();
        tickRepo.save(t1);
        tickRepo.save(t2);

        Instant from = Instant.now().minusSeconds(120);
        Instant to = Instant.now();
        List<Tick> out = tickRepo.findBySymbolAndTsBetweenOrderByTsAsc("NIFTY", from, to);

        assertThat(out).hasSizeGreaterThanOrEqualTo(2);
        assertThat(out.get(0).getTs()).isBefore(out.get(1).getTs());
    }
}
