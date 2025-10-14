package com.trade.frankenstein.trader.service.trade;

import com.trade.frankenstein.trader.bus.EventPublisher;
import com.trade.frankenstein.trader.model.documents.OutboxEvent;
import com.trade.frankenstein.trader.repo.documents.OutboxEventRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
public class OutboxPublisher {

    @Autowired
    private OutboxEventRepo outboxEventRepo;

    @Autowired
    private EventPublisher eventPublisher;

    @Scheduled(fixedRateString = "${trade.outbox.publish-interval-ms:1000}")
    public void publishPendingEvents() {
        try {
            List<OutboxEvent> pendingEvents = outboxEventRepo.findByPublishedFalseOrderByCreatedAtAsc();

            if (pendingEvents.isEmpty()) {
                return;
            }

            log.debug("Publishing {} outbox events", pendingEvents.size());

            for (OutboxEvent event : pendingEvents) {
                try {
                    eventPublisher.publish(event.getTopic(), event.getKey(), event.getPayload());

                    // Mark as published
                    event.setPublished(true);
                    event.setPublishedAt(Instant.now());
                    outboxEventRepo.save(event);

                    log.debug("Published outbox event: {}", event.getId());
                } catch (Exception e) {
                    log.error("Failed to publish outbox event: {}", event.getId(), e);
                    // Will retry on next cycle
                }
            }
        } catch (Exception e) {
            log.error("Error in outbox publisher", e);
        }
    }
}
