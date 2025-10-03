package com.trade.frankenstein.trader.service;

import com.trade.frankenstein.trader.bus.KafkaPropertiesHelper;
import com.trade.frankenstein.trader.common.AuthCodeHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StreamGateway — SSE fan-out layer.
 * <p>
 * Java 8, no reflection. Additive methods only.
 * Now supports subscribing to multiple topics in a single call.
 */
@Slf4j
@Service
public class StreamGateway {

    private static final long DEFAULT_EMITTER_TIMEOUT_MS = 0L; // never timeout; we manage it
    private static final long HEARTBEAT_MS = 15000L;           // 15s

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<String, SseEmitter>();
    private final Map<String, Set<String>> topicSubs = new ConcurrentHashMap<String, Set<String>>();
    private final Set<String> csvEmitters = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean(false);

    // Step-10: Kafka consumer → SSE fan-out (advice, trade, risk)
    private final AtomicBoolean kafkaStarted = new AtomicBoolean(false);
    private ExecutorService kafkaExec;


    @Autowired(required = false)
    private TaskScheduler taskScheduler; // optional; if missing, heartbeat won't start

    // ------------------------ SUBSCRIBE ------------------------

    /**
     * NEW: Subscribe with multiple topics.
     * If topics is null/empty, the emitter is created but not subscribed to any topic yet.
     */
    public @Nullable SseEmitter subscribe(@Nullable String id, @Nullable List<String> topics) {
        if (!AuthCodeHolder.getInstance().isLoggedIn()) {
            log.info(" User not logged in");
            return null;
        }
        final String emitterId = (StringUtils.hasText(id) ? id : UUID.randomUUID().toString());
        final SseEmitter emitter = new SseEmitter(DEFAULT_EMITTER_TIMEOUT_MS);

        final SseEmitter previous = emitters.put(emitterId, emitter);
        if (previous != null) {
            try {
                previous.complete();
            } catch (Throwable ignore) { /* no-op */ }
        }

        // Subscribe to provided topics (if any)
        if (topics != null) {
            for (String t : topics) {
                if (StringUtils.hasText(t)) {
                    addSubscription(emitterId, t.trim());
                }
            }
        }

        emitter.onCompletion(new Runnable() {
            @Override
            public void run() {
                removeEmitter(emitterId, "completion");
            }
        });
        emitter.onTimeout(new Runnable() {
            @Override
            public void run() {
                removeEmitter(emitterId, "timeout");
            }
        });

        try {
            emitter.send(SseEmitter.event().name("connected").data(emitterId));
        } catch (IOException | IllegalStateException ex) {
            log.warn("SSE send(connect) failed for id={}, removing. cause={}", emitterId, ex.toString());
            removeEmitter(emitterId, "io-on-connect");
        }

        startHeartbeatIfNeeded();
        return emitter;
    }

    /**
     * BACKWARD-COMPAT: Single-topic variant kept to avoid breaking callers.
     * Delegates to the multi-topic overload.
     */
    public @Nullable SseEmitter subscribe(@Nullable String id, @Nullable String topic) {
        List<String> topics = null;
        if (StringUtils.hasText(topic)) {
            topics = java.util.Collections.singletonList(topic);
        }
        return subscribe(id, topics);
    }

    /**
     * NEW: CSV variant with multiple topics.
     */
    public @Nullable SseEmitter subscribeCsv(@Nullable String id, @Nullable List<String> topics) {
        final SseEmitter emitter = subscribe(id, topics);
        if (emitter == null) return null;

        final String emitterId = findIdByEmitter(emitter);
        if (emitterId != null) {
            csvEmitters.add(emitterId);
        }
        return emitter;
    }

    /**
     * BACKWARD-COMPAT: CSV single-topic variant.
     */
    public @Nullable SseEmitter subscribeCsv(@Nullable String id, @Nullable String topic) {
        List<String> topics = null;
        if (StringUtils.hasText(topic)) {
            topics = java.util.Collections.singletonList(topic);
        }
        return subscribeCsv(id, topics);
    }


    /**
     * Generic local send for in-process producers (kept for backward-compat with existing services).
     * Example topic: "advice.new" → eventName "advice".
     */
    public void send(String topic, Object payload) {
        if (!StringUtils.hasText(topic)) return;
        final String eventName;
        final int dot = topic.indexOf('.');
        if (dot > 0) {
            eventName = topic.substring(0, dot);
        } else {
            eventName = topic;
        }
        publish(topic, eventName, payload);
    }

    // ------------------------ PUBLISH ------------------------

    private String normalizeTopic(String base, String subTopic) {
        if (!StringUtils.hasText(subTopic)) return base;
        final String t = subTopic.trim();
        if (t.startsWith(base + ".")) return t;
        return base + "." + t;
    }

    public void publishDecision(String subTopic, Object payload) {
        final String topic = normalizeTopic("decision", subTopic);
        publish(topic, "decision", payload);
    }

    public void publishRisk(String subTopic, Object payload) {
        final String topic = normalizeTopic("risk", subTopic);
        publish(topic, "risk", payload);
    }

    public void publishAdvice(String subTopic, Object payload) {
        final String topic = normalizeTopic("advice", subTopic);
        publish(topic, "advice", payload);
    }

    public void publishOrder(String subTopic, Object payload) {
        final String topic = normalizeTopic("order", subTopic);
        publish(topic, "order", payload);
    }


    public void publishAudit(String subTopic, Object payload) {
        final String topic = normalizeTopic("audit", subTopic);
        publish(topic, "audit", payload);
    }

    public void publishTicks(String subTopic, Object payload) {
        final String topic = normalizeTopic("ticks", subTopic);
        publish(topic, "ticks", payload);
    }

    public void publishOptionChain(String subTopic, Object payload) {
        final String topic = normalizeTopic("option_chain", subTopic);
        publish(topic, "option_chain", payload);
    }

    public void publishTrade(String subTopic, Object payload) {
        final String topic = normalizeTopic("trade", subTopic);
        publish(topic, "trade", payload);
    }

    public void publish(String topic, String eventName, Object payload) {
        if (!StringUtils.hasText(topic)) {
            log.debug("publish ignored: blank topic");
            return;
        }
        final Set<String> ids = topicSubs.get(topic);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        final List<String> toRemove = new ArrayList<String>();

        for (String id : ids) {
            final SseEmitter emitter = emitters.get(id);
            if (emitter == null) {
                toRemove.add(id);
                continue;
            }
            try {
                final boolean asCsv = csvEmitters.contains(id);
                Object data = asCsv ? toCsv(payload) : payload;
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (IOException | IllegalStateException ex) {
                log.warn("SSE send failed for id={} topic={} event={}, removing. cause={}", id, topic, eventName, ex.toString());
                toRemove.add(id);
            }
        }
        for (String deadId : toRemove) {
            removeEmitter(deadId, "send-failed:" + topic);
        }
    }


    // ------------------------ KAFKA CONSUMER (Step-10) ------------------------

    /**
     * On startup, create a lightweight KafkaConsumer that subscribes to advice.*, trade.*, risk.*
     * and fans-out each consumed record to SSE subscribers.
     */
    @PostConstruct
    public void startKafkaFanout() {
        if (kafkaStarted.compareAndSet(false, true)) {
            kafkaExec = Executors.newSingleThreadExecutor();
            kafkaExec.submit(new Runnable() {
                @Override
                public void run() {
                    runKafkaLoop();
                }
            });
        }
    }

    private void runKafkaLoop() {
        Properties p = KafkaPropertiesHelper.loadConsumerProps();
        try {
            // Load defaults from classpath (same file as EventPublisher)
            InputStream in = StreamGateway.class.getResourceAsStream("/event-bus.properties");
            if (in != null) {
                try {
                    p.load(in);
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Exception ignore) { /* no-op */ }

        // Minimal sane defaults
        if (!p.containsKey("bootstrap.servers")) {
            String brokers = System.getenv("KAFKA_BROKERS");
            if (brokers != null && brokers.trim().length() > 0) p.put("bootstrap.servers", brokers);
        }
        p.put("group.id", p.getProperty("group.id", "stream-gateway"));
        p.put("enable.auto.commit", p.getProperty("enable.auto.commit", "true"));
        p.put("auto.offset.reset", p.getProperty("auto.offset.reset", "latest"));
        p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");

        KafkaConsumer<String, String> consumer = null;
        try {
            consumer = new KafkaConsumer<String, String>(p);

            // Prefer regex subscription if available
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^(advice|trade|risk|decision)\\..+?$");
            try {
                consumer.subscribe(pattern);
            } catch (Throwable t) {
                // Fallback to static topic list
                java.util.List<String> topics = new java.util.ArrayList<String>();
                topics.add("advice");
                topics.add("trade");
                topics.add("risk");
                topics.add("decision");
                topics.add("audit");
                topics.add("ticks");
                topics.add("option_chain");
                topics.add("order");
                consumer.subscribe(topics);
            }

            while (!Thread.currentThread().isInterrupted()) {
                ConsumerRecords<String, String> records = consumer.poll(1000);
                if (records == null) continue;
                for (ConsumerRecord<String, String> r : records) {
                    try {
                        onKafkaMessage(r.topic(), r.key(), r.value());
                    } catch (Throwable t) {
                        // swallow to keep loop alive
                    }
                }
            }
        } catch (Throwable t) {
            // If broken, log once and stop silently (SSE still works for in-process send(...))
            try {
                log.error("Kafka fan-out loop failed: {}", t.toString());
            } catch (Throwable ignore) {
            }
        } finally {
            try {
                if (consumer != null) consumer.close();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * Map a Kafka topic into SSE topic+event and publish to subscribers.
     */
    private void onKafkaMessage(String topic, String key, String value) {
        if (topic == null || value == null) return;
        final String eventName;
        final int dot = topic.indexOf('.');
        if (dot > 0) {
            eventName = topic.substring(0, dot);
        } else {
            eventName = topic;
        }
        publish(topic, eventName, value);
    }
// ------------------------ SUBSCRIPTIONS ------------------------

    public void addSubscription(String emitterId, String topic) {
        if (!StringUtils.hasText(topic) || !emitters.containsKey(emitterId)) return;
        topicSubs.computeIfAbsent(topic, new java.util.function.Function<String, Set<String>>() {
            @Override
            public Set<String> apply(String k) {
                return new CopyOnWriteArraySet<String>();
            }
        }).add(emitterId);
    }

    public void removeSubscription(String emitterId, String topic) {
        if (!StringUtils.hasText(topic)) return;
        final Set<String> set = topicSubs.get(topic);
        if (set != null) {
            set.remove(emitterId);
            if (set.isEmpty()) {
                topicSubs.remove(topic);
            }
        }
    }

    // ------------------------ UTILITIES ------------------------

    public void removeEmitter(String id, String reason) {
        final SseEmitter emitter = emitters.remove(id);
        csvEmitters.remove(id);
        for (Map.Entry<String, Set<String>> e : topicSubs.entrySet()) {
            e.getValue().remove(id);
        }
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Throwable ignore) { /* no-op */ }
        }
        log.debug("Emitter removed id={}, reason={}", id, reason);
    }

    private String findIdByEmitter(SseEmitter emitter) {
        for (Map.Entry<String, SseEmitter> e : emitters.entrySet()) {
            if (e.getValue() == emitter) return e.getKey();
        }
        return null;
    }

    private void startHeartbeatIfNeeded() {
        if (taskScheduler == null) {
            if (heartbeatStarted.compareAndSet(false, false)) {
                log.info("No TaskScheduler bean found; SSE heartbeat disabled.");
            }
            return;
        }
        if (heartbeatStarted.compareAndSet(false, true)) {
            taskScheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    final List<String> toRemove = new ArrayList<String>();
                    for (Map.Entry<String, SseEmitter> e : emitters.entrySet()) {
                        try {
                            e.getValue().send(SseEmitter.event().name("heartbeat").data("♥"));
                        } catch (IOException | IllegalStateException ex) {
                            toRemove.add(e.getKey());
                        }
                    }
                    for (String id : toRemove) {
                        removeEmitter(id, "heartbeat-failed");
                    }
                }
            }, HEARTBEAT_MS);
            log.info("SSE heartbeat scheduled every {} ms", HEARTBEAT_MS);
        }
    }

    private String toCsv(Object payload) {
        if (payload == null) return "";
        if (payload instanceof CharSequence) return payload.toString();
        if (payload instanceof Number || payload instanceof Boolean) return String.valueOf(payload);
        if (payload instanceof Map map) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object k : map.keySet()) {
                if (!first) sb.append(',');
                Object v = map.get(k);
                sb.append(escapeCsv(String.valueOf(k))).append(',').append(escapeCsv(String.valueOf(v)));
                first = false;
            }
            return sb.toString();
        }
        if (payload instanceof java.lang.Iterable) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Object v : (Iterable) payload) {
                if (!first) sb.append(',');
                sb.append(escapeCsv(String.valueOf(v)));
                first = false;
            }
            return sb.toString();
        }
        return escapeCsv(String.valueOf(payload));
    }

    private String escapeCsv(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!needsQuotes) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    @PreDestroy
    public void stopKafkaFanout() {
        try {
            if (kafkaExec != null) {
                kafkaExec.shutdownNow();
            }
        } catch (Throwable ignore) {
        }
    }

}