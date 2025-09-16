// src/main/java/com/trade/frankenstein/trader/service/streaming/StreamGateway.java
package com.trade.frankenstein.trader.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE hub for UI-v2 topics.
 * <p>
 * Features:
 * - send(topic, payload): broadcast to exact and wildcard subscribers.
 * - sendSticky(topic, payload): broadcast + remember last payload for replay.
 * - subscribe(timeoutMs, topics): topics can be exact (e.g. "risk.summary") or wildcard ("advice.*").
 * - Heartbeat: optional, if a TaskScheduler is available.
 * <p>
 * Notes:
 * - This is single-node. For multi-node, back with a pub/sub backplane (e.g., Redis).
 * - Java 8 compatible (no Duration APIs).
 */
@Service
@Slf4j
public class StreamGateway {

    private static final long DEFAULT_TIMEOUT_MS = 30L * 60L * 1000L;  // 30 minutes
    private static final long HEARTBEAT_MS = 20_000L;                  // 20 seconds

    @Autowired(required = false)
    @Nullable
    private TaskScheduler taskScheduler;

    private final AtomicBoolean heartbeatScheduled = new AtomicBoolean(false);

    // ---- Registries ----
    /**
     * Emitter id → emitter
     */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    /**
     * Emitter id → exact topics (e.g., "risk.summary")
     */
    private final Map<String, Set<String>> emitterExactTopics = new ConcurrentHashMap<>();
    /**
     * Emitter id → wildcard prefixes (store without the final '*', e.g., "advice.")
     */
    private final Map<String, Set<String>> emitterPrefixes = new ConcurrentHashMap<>();
    /**
     * Exact topic → emitter ids subscribed to it (fast exact routing)
     */
    private final Map<String, Set<String>> topicToEmitters = new ConcurrentHashMap<>();
    /**
     * Optional in-memory "last event" cache for replay (topic → payload)
     */
    private final Map<String, Object> stickyByTopic = new ConcurrentHashMap<>();

    // ========================= Public API =========================

    /**
     * Broadcast a payload to all subscribers of the given topic.
     */
    public <T> void send(String topic, T payload) {
        if (!StringUtils.hasText(topic)) return;

        final Set<String> targets = collectTargets(topic);
        if (targets.isEmpty()) {
            log.trace("SSE send: no subscribers for topic={}", topic);
            return;
        }

        int sent = fanOut(topic, payload, targets);
        if (sent > 0) {
            log.debug("SSE send: topic={} -> delivered to {} subscriber(s)", topic, sent);
        }
    }

    /**
     * Broadcast + remember the payload for replay to new subscribers.
     */
    public <T> void sendSticky(String topic, T payload) {
        if (!StringUtils.hasText(topic)) return;
        stickyByTopic.put(topic, payload);
        send(topic, payload);
    }

    /**
     * Create and register a new SSE emitter with given topic subscriptions.
     *
     * @param timeoutMs     null or <= 0 uses default (30m)
     * @param subscriptions topics like "risk.summary" and/or "advice.*"
     */
    public SseEmitter subscribe(@Nullable Long timeoutMs, Collection<String> subscriptions) {
        final long to = (timeoutMs == null || timeoutMs <= 0) ? DEFAULT_TIMEOUT_MS : timeoutMs;
        final SseEmitter emitter = new SseEmitter(to);
        final String id = UUID.randomUUID().toString();

        emitters.put(id, emitter);

        // Parse subscriptions into exact topics vs wildcard prefixes
        final Set<String> exact = new LinkedHashSet<>();
        final Set<String> prefixes = new LinkedHashSet<>();
        if (subscriptions != null) {
            for (String s : subscriptions) {
                if (!StringUtils.hasText(s)) continue;
                s = s.trim();
                if (s.endsWith(".*")) {
                    // keep trailing dot for prefix matching (e.g., "advice.")
                    prefixes.add(s.substring(0, s.length() - 1));
                } else {
                    exact.add(s);
                }
            }
        }
        emitterExactTopics.put(id, exact);
        emitterPrefixes.put(id, prefixes);

        // Back-references for fast exact-topic routing
        for (String t : exact) {
            topicToEmitters.computeIfAbsent(t, k -> ConcurrentHashMap.newKeySet()).add(id);
        }

        // Cleanup hooks
        emitter.onCompletion(() -> removeEmitter(id, "completion"));
        emitter.onTimeout(() -> removeEmitter(id, "timeout"));
        emitter.onError(e -> removeEmitter(id, "error: " + (e == null ? "unknown" : e.getClass().getSimpleName())));

        // Initial hello
        try {
            emitter.send(SseEmitter.event().name("init").data("ok"));
        } catch (IOException ex) {
            log.debug("SSE init failed for emitter={}, removing. Reason: {}", id, ex.toString());
            removeEmitter(id, "init-failed");
            return emitter;
        }

        // Optional replay of sticky events for immediate UI hydration
        int replayCount = replaySticky(id, exact, prefixes);

        log.info("SSE subscribed: id={}, exact={}, prefixes={}, replayed={} nowTotal={}",
                id, exact, prefixes, replayCount, emitters.size());

        // Start heartbeat loop (if scheduler exists)
        startHeartbeatIfNeeded();

        return emitter;
    }

    /**
     * Convenience: subscribe via comma-separated topic list.
     */
    public SseEmitter subscribeCsv(@Nullable Long timeoutMs, @Nullable String csvTopics) {
        final List<String> subs = new ArrayList<>();
        if (StringUtils.hasText(csvTopics)) {
            final String[] parts = csvTopics.split(",");
            for (int i = 0; i < parts.length; i++) {
                final String s = parts[i] == null ? null : parts[i].trim();
                if (StringUtils.hasText(s)) subs.add(s);
            }
        }
        return subscribe(timeoutMs, subs);
    }

    /**
     * Diagnostics/testing helper.
     */
    public int subscriberCount() {
        return emitters.size();
    }

    /**
     * Clears the sticky cache (useful for tests or reboots).
     */
    public void clearSticky() {
        stickyByTopic.clear();
        log.info("SSE sticky cache cleared");
    }

    // ========================= Internals =========================

    private Set<String> collectTargets(String topic) {
        final Set<String> targets = new LinkedHashSet<>();

        // Exact matches
        final Set<String> exact = topicToEmitters.get(topic);
        if (exact != null && !exact.isEmpty()) targets.addAll(exact);

        // Prefix (wildcard) matches
        for (Map.Entry<String, Set<String>> e : emitterPrefixes.entrySet()) {
            final String emitterId = e.getKey();
            final Set<String> prefixes = e.getValue();
            if (prefixes == null || prefixes.isEmpty()) continue;
            for (String p : prefixes) {
                if (topic.startsWith(p)) {
                    targets.add(emitterId);
                    break;
                }
            }
        }
        return targets;
    }

    private <T> int fanOut(String topic, T payload, Set<String> targets) {
        int delivered = 0;
        for (String id : targets) {
            final SseEmitter em = emitters.get(id);
            if (em == null) continue;
            try {
                em.send(SseEmitter.event().name(topic).data(payload));
                delivered++;
            } catch (IOException | IllegalStateException ex) {
                log.warn("SSE send failed; pruning emitter id={}, topic={}, reason={}", id, topic, ex.toString());
                removeEmitter(id, "send-failed");
            }
        }
        return delivered;
    }

    private int replaySticky(String emitterId, Set<String> exact, Set<String> prefixes) {
        int replayed = 0;
        if (stickyByTopic.isEmpty()) return 0;

        // Exact topics first
        for (String t : exact) {
            final Object payload = stickyByTopic.get(t);
            if (payload == null) continue;
            final SseEmitter em = emitters.get(emitterId);
            if (em == null) break;
            try {
                em.send(SseEmitter.event().name(t).data(payload));
                replayed++;
            } catch (IOException | IllegalStateException ex) {
                log.debug("replaySticky : SSE replay failed; pruning emitter id={}, topic={}, reason={}", emitterId, t, ex.toString());
                removeEmitter(emitterId, "replay-failed");
                return replayed;
            }
        }

        // Wildcard topics (prefixes)
        if (!prefixes.isEmpty()) {
            final SseEmitter em = emitters.get(emitterId);
            if (em != null) {
                for (Map.Entry<String, Object> entry : stickyByTopic.entrySet()) {
                    final String topic = entry.getKey();
                    if (topic == null) continue;
                    if (matchesAnyPrefix(topic, prefixes)) {
                        try {
                            em.send(SseEmitter.event().name(topic).data(entry.getValue()));
                            replayed++;
                        } catch (IOException | IllegalStateException ex) {
                            log.debug("SSE replay failed; pruning emitter id={}, topic={}, reason={}", emitterId, topic, ex.toString());
                            removeEmitter(emitterId, "replay-failed");
                            return replayed;
                        }
                    }
                }
            }
        }
        return replayed;
    }

    private boolean matchesAnyPrefix(String topic, Set<String> prefixes) {
        for (String p : prefixes) {
            if (topic.startsWith(p)) return true;
        }
        return false;
    }

    private void removeEmitter(String id, String reason) {
        final SseEmitter em = emitters.remove(id);
        if (em != null) {
            try {
                em.complete();
            } catch (Throwable ignored) {
            }
        }

        final Set<String> exact = emitterExactTopics.remove(id);
        if (exact != null) {
            for (String t : exact) {
                final Set<String> ids = topicToEmitters.get(t);
                if (ids != null) {
                    ids.remove(id);
                    if (ids.isEmpty()) topicToEmitters.remove(t);
                }
            }
        }
        emitterPrefixes.remove(id);

        log.info("SSE unsubscribed: id={}, reason={}, nowTotal={}", id, reason, emitters.size());
    }

    private void startHeartbeatIfNeeded() {
        if (taskScheduler == null) return;
        if (heartbeatScheduled.compareAndSet(false, true)) {
            taskScheduler.scheduleAtFixedRate(() -> {
                if (emitters.isEmpty()) return;
                final List<String> toRemove = new ArrayList<>();
                for (Map.Entry<String, SseEmitter> e : emitters.entrySet()) {
                    try {
                        e.getValue().send(SseEmitter.event().name("heartbeat").data("♥"));
                    } catch (IOException | IllegalStateException ex) {
                        toRemove.add(e.getKey());
                    }
                }
                for (String s : toRemove) {
                    removeEmitter(s, "heartbeat-failed");
                }
            }, HEARTBEAT_MS);
            log.info("SSE heartbeat scheduled every {} ms", HEARTBEAT_MS);
        }
    }
}
