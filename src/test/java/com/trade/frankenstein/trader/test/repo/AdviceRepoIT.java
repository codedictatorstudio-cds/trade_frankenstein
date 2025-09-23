package com.trade.frankenstein.trader.test.repo;

import com.trade.frankenstein.trader.test.BaseContainers;
import com.trade.frankenstein.trader.enums.AdviceStatus;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.repo.documents.AdviceRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataMongoTest
@ActiveProfiles("test")
class AdviceRepoIT extends BaseContainers {

    @Autowired AdviceRepo adviceRepo;

    @Test
    void saveAndFindByStatusAndAsOfDesc() {
        Advice a1 = new Advice();
        a1.setStatus(AdviceStatus.PENDING);
        a1.setCreatedAt(Instant.now().minusSeconds(60));

        Advice a2 = new Advice();
        a2.setStatus(AdviceStatus.PENDING);
        a2.setCreatedAt(Instant.now());

        adviceRepo.save(a1);
        adviceRepo.save(a2);

        List<Advice> found = adviceRepo.findByStatusOrderByCreatedAtDesc(AdviceStatus.PENDING);
        assertThat(found).hasSizeGreaterThanOrEqualTo(2);
        assertThat(found.get(0).getCreatedAt()).isAfterOrEqualTo(found.get(1).getCreatedAt());
    }
}
