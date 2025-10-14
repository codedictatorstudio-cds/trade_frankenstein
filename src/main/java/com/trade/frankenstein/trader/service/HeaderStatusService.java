package com.trade.frankenstein.trader.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Service to provide header status updates via Reactor streams.
 */
@Service
public class HeaderStatusService {

    public enum StatusType {
        ENGINE_STATUS,
        SSE_HEALTH,
        PERFORMANCE_METRICS,
        SYSTEM_INFO;
    }

    public static class StatusUpdate {
        private final StatusType type;
        private final JsonNode data;

        public StatusUpdate(StatusType type, JsonNode data) {
            this.type = type;
            this.data = data;
        }

        public StatusType getType() {
            return type;
        }

        public JsonNode getData() {
            return data;
        }
    }

    private final Sinks.Many<StatusUpdate> sink = Sinks.many().multicast().onBackpressureBuffer();

    /**
     * Start periodic status polling and SSE subscription
     */
    public void start() {
        // Example periodic heartbeat emitter
        Flux.interval(Duration.ofSeconds(5))
                .map(tick -> new StatusUpdate(StatusType.SYSTEM_INFO, null))
                .subscribe(sink::tryEmitNext);
    }

    /**
     * Stop all subscriptions and polling
     */
    public void stop() {
        // No-op for example
    }

    /**
     * Subscribe to status updates
     */
    public Disposable subscribe(Consumer<StatusUpdate> consumer) {
        return sink.asFlux().subscribe(consumer);
    }
}