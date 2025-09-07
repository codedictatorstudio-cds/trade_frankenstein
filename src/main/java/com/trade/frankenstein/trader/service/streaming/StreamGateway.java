// src/main/java/com/trade/frankenstein/trader/service/streaming/StreamGateway.java
package com.trade.frankenstein.trader.service.streaming;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE hub for UI-v2 topics.
 * - send(topic, payload): fan-out to all subscribers of a topic (and matching wildcard prefixes).
 * - subscribe(timeoutMs, topics): controller calls this to create an SseEmitter.
 *
 * Wildcards: pass "advice.*" to receive any topic starting with "advice." (e.g., "advice.new").
 * Heartbeat: if a TaskScheduler is available, sends "heartbeat" events periodically.
 *
 * NOTE: For multi-node deployments, back this with a pub/sub backplane (e.g., Redis)
 * so send() on node A reaches subscribers on node B. This class is single-node by itself.
 */
@Service
@Slf4j
public class StreamGateway {

    // ---- Tunables ----
    private static final long DEFAULT_TIMEOUT_MS = 30L * 60L * 1000L;  // 30 minutes
    private static final Duration HEARTBEAT_EVERY = Duration.ofSeconds(20);

    @Autowired(required = false)
    @Nullable
    private TaskScheduler taskScheduler;

    private final AtomicBoolean heartbeatScheduled = new AtomicBoolean(false);

    // ---- Registry ----
    /** Emitter id → emitter */
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    /** Emitter id → exact topics (e.g., "risk.summary") */
    private final Map<String, Set<String>> emitterExactTopics = new ConcurrentHashMap<>();
    /** Emitter id → wildcard prefixes (store without the final '*', e.g., "advice.") */
    private final Map<String, Set<String>> emitterPrefixes = new ConcurrentHashMap<>();
    /** Exact topic → emitter ids subscribed to it */
    private final Map<String, Set<String>> topicToEmitters = new ConcurrentHashMap<>();

    // ========================= Public API =========================

    /** Broadcast a payload to all subscribers of the given topic. */
    public <T> void send(String topic, T payload) {
        if (!StringUtils.hasText(topic)) return;

        // Snapshot target ids (avoid concurrent modification)
        Set<String> targets = new LinkedHashSet<>();

        // Exact matches
        Set<String> exact = topicToEmitters.get(topic);
        if (exact != null) targets.addAll(exact);

        // Prefix (wildcard) matches
        for (Map.Entry<String, Set<String>> e : emitterPrefixes.entrySet()) {
            String emitterId = e.getKey();
            Set<String> prefixes = e.getValue();
            if (prefixes == null || prefixes.isEmpty()) continue;
            for (String p : prefixes) {
                if (topic.startsWith(p)) {
                    targets.add(emitterId);
                    break;
                }
            }
        }

        if (targets.isEmpty()) return;

        // Fan-out, removing broken emitters
        for (String id : targets) {
            SseEmitter em = emitters.get(id);
            if (em == null) continue;
            try {
                em.send(SseEmitter.event().name(topic).data(payload));
            } catch (IOException | IllegalStateException ex) {
                log.debug("SSE send failed; pruning emitter {}", id, ex);
                removeEmitter(id);
            }
        }
    }

    /**
     * Controller helper: create and register a new emitter.
     *
     * @param timeoutMs     null or <=0 uses default (30m)
     * @param subscriptions topics like "risk.summary" and/or "advice.*"
     */
    public SseEmitter subscribe(@Nullable Long timeoutMs, Collection<String> subscriptions) {
        final long to = (timeoutMs == null || timeoutMs <= 0) ? DEFAULT_TIMEOUT_MS : timeoutMs;
        final SseEmitter emitter = new SseEmitter(to);
        final String id = UUID.randomUUID().toString();

        emitters.put(id, emitter);

        // Parse subscriptions into exact topics vs wildcard prefixes
        Set<String> exact = new LinkedHashSet<>();
        Set<String> prefixes = new LinkedHashSet<>();
        if (subscriptions != null) {
            for (String s : subscriptions) {
                if (!StringUtils.hasText(s)) continue;
                s = s.trim();
                if (s.endsWith(".*")) {
                    prefixes.add(s.substring(0, s.length() - 1)); // keep trailing dot
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
        emitter.onCompletion(() -> removeEmitter(id));
        emitter.onTimeout(() -> removeEmitter(id));
        emitter.onError(e -> removeEmitter(id));

        // Initial hello
        try {
            emitter.send(SseEmitter.event().name("init").data("ok"));
        } catch (IOException ignored) {
            removeEmitter(id);
        }

        // Start heartbeat loop (if scheduler exists)
        startHeartbeatIfNeeded();

        return emitter;
    }

    /** Optional convenience for controllers that accept comma-separated query. */
    public SseEmitter subscribeCsv(@Nullable Long timeoutMs, @Nullable String csvTopics) {
        List<String> subs = new ArrayList<>();
        if (StringUtils.hasText(csvTopics)) {
            for (String s : csvTopics.split(",")) {
                if (StringUtils.hasText(s)) subs.add(s.trim());
            }
        }
        return subscribe(timeoutMs, subs);
    }

    /** For diagnostics/testing (not required by UI). */
    public int subscriberCount() {
        return emitters.size();
    }

    // ========================= Internals =========================

    private void removeEmitter(String id) {
        SseEmitter em = emitters.remove(id);
        if (em != null) {
            try { em.complete(); } catch (Throwable ignored) {}
        }
        Set<String> exact = emitterExactTopics.remove(id);
        if (exact != null) {
            for (String t : exact) {
                Set<String> ids = topicToEmitters.get(t);
                if (ids != null) {
                    ids.remove(id);
                    if (ids.isEmpty()) topicToEmitters.remove(t);
                }
            }
        }
        emitterPrefixes.remove(id);
    }

    private void startHeartbeatIfNeeded() {
        if (taskScheduler == null) return;
        if (heartbeatScheduled.compareAndSet(false, true)) {
            taskScheduler.scheduleAtFixedRate(() -> {
                if (emitters.isEmpty()) return;
                for (Map.Entry<String, SseEmitter> e : emitters.entrySet()) {
                    try {
                        e.getValue().send(SseEmitter.event().name("heartbeat").data("♥"));
                    } catch (IOException | IllegalStateException ex) {
                        removeEmitter(e.getKey());
                    }
                }
            }, HEARTBEAT_EVERY);
            log.info("SSE heartbeat every {}s", HEARTBEAT_EVERY.getSeconds());
        }
    }
}
