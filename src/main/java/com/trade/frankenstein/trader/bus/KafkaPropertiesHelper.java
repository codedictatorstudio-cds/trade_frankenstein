package com.trade.frankenstein.trader.bus;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

/**
 * KafkaPropertiesHelper
 * ------------------------------------------------------------
 * Centralizes how producer/consumer Properties are loaded and
 * guarantees a single source of truth for bootstrap.servers.
 *
 * Java 8 compatible. No reflection. No generics trickery.
 *
 * Resolution precedence for bootstrap.servers:
 *  1) Env: KAFKA_BOOTSTRAP_SERVERS
 *  2) Sys Prop: kafka.bootstrap.servers
 *  3) application-{profile}.properties -> tf.kafka.bootstrap-servers
 *  4) application.properties           -> tf.kafka.bootstrap-servers
 *  5) producer/consumer.properties     -> bootstrap.servers (as-is)
 *
 * Also supports simple ${...} placeholder expansion inside
 * producer.properties / consumer.properties using application*.properties
 * as the source of placeholder values.
 */
public final class KafkaPropertiesHelper {

    private static final String APP_PROPS = "/application.properties";
    private static final String APP_PROPS_FMT = "/application-%s.properties";
    private static final String PRODUCER_PROPS = "/producer.properties";
    private static final String CONSUMER_PROPS = "/consumer.properties";

    private static final String TF_BOOTSTRAP_KEY = "tf.kafka.bootstrap-servers";
    private static final String BOOTSTRAP_KEY = "bootstrap.servers";
    private static final String SPRING_PROFILES_ACTIVE = "spring.profiles.active";

    private KafkaPropertiesHelper() { /* no instances */ }

    /* ---------------- Public API ---------------- */

    public static Properties loadProducerProps() {
        Properties base = readProps(PRODUCER_PROPS);
        Properties app = loadAppProps();
        expandPlaceholders(base, app);
        String bs = resolveBootstrapServers(app);
        if (bs != null && !bs.trim().isEmpty()) {
            base.setProperty(BOOTSTRAP_KEY, bs.trim());
        }
        hardenProducerDefaults(base);
        return base;
    }

    public static Properties loadConsumerProps() {
        Properties base = readProps(CONSUMER_PROPS);
        Properties app = loadAppProps();
        expandPlaceholders(base, app);
        String bs = resolveBootstrapServers(app);
        if (bs != null && !bs.trim().isEmpty()) {
            base.setProperty(BOOTSTRAP_KEY, bs.trim());
        }
        hardenConsumerDefaults(base);
        return base;
    }

    public static String resolveBootstrapServers(Properties appProps) {
        // 1) Env
        String env = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
        if (notBlank(env)) return env.trim();

        // 2) JVM System property
        String sys = System.getProperty("kafka.bootstrap.servers");
        if (notBlank(sys)) return sys.trim();

        // 3/4) application-{profile}.properties / application.properties
        if (appProps != null) {
            String tf = appProps.getProperty(TF_BOOTSTRAP_KEY);
            if (notBlank(tf)) return tf.trim();
        }

        // 5) No override: return null -> keep what's in producer/consumer.properties
        return null;
    }

    public static Properties loadAppProps() {
        Properties app = new Properties();

        // Attempt profile-specific first
        String profile = System.getProperty(SPRING_PROFILES_ACTIVE);
        if (!notBlank(profile)) {
            profile = System.getenv(toEnvKey(SPRING_PROFILES_ACTIVE));
        }
        if (notBlank(profile)) {
            Properties prof = readProps(String.format(APP_PROPS_FMT, profile.trim()));
            if (prof != null) {
                app.putAll(prof);
            }
        }

        // Then baseline application.properties
        Properties base = readProps(APP_PROPS);
        if (base != null) {
            for (Map.Entry<Object, Object> e : base.entrySet()) {
                if (!app.containsKey(e.getKey())) {
                    app.put(e.getKey(), e.getValue());
                }
            }
        }
        return app;
    }

    /* ---------------- Internals ---------------- */

    private static Properties readProps(String classpathResource) {
        Properties p = new Properties();
        try (InputStream in = KafkaPropertiesHelper.class.getResourceAsStream(classpathResource)) {
            if (in == null) return p; // return empty if not found
            p.load(in);
            return p;
        } catch (IOException e) {
            // Return what we have; callers can proceed with defaults
            return p;
        }
    }

    /** Minimal ${key} placeholder expansion using application*.properties as source. */
    private static void expandPlaceholders(Properties target, Properties source) {
        if (target == null || source == null || target.isEmpty()) return;

        for (Map.Entry<Object, Object> e : target.entrySet()) {
            Object val = e.getValue();
            if (val instanceof String) {
                String s = (String) val;
                String expanded = expandOne(s, source);
                if (!s.equals(expanded)) {
                    target.setProperty(String.valueOf(e.getKey()), expanded);
                }
            }
        }
    }

    private static String expandOne(String value, Properties source) {
        String out = value;
        int safety = 0;
        // Resolve nested placeholders up to a sensible cap
        while (out.contains("${") && safety < 10) {
            int start = out.indexOf("${");
            int end = out.indexOf("}", start + 2);
            if (start < 0 || end < 0) break;
            String key = out.substring(start + 2, end);
            String repl = source.getProperty(key);
            if (repl == null) repl = System.getProperty(key);
            if (repl == null) repl = System.getenv(toEnvKey(key));
            if (repl == null) repl = ""; // drop unresolved to empty
            out = out.substring(0, start) + repl + out.substring(end + 1);
            safety++;
        }
        return out;
    }

    private static String toEnvKey(String key) {
        // spring.profiles.active -> SPRING_PROFILES_ACTIVE
        return key == null ? null : key.trim().toUpperCase().replace('.', '_').replace('-', '_');
    }

    private static boolean notBlank(String s) {
        return s != null && s.trim().length() > 0;
    }

    private static void hardenProducerDefaults(Properties p) {
        putIfAbsent(p, "acks", "all");
        putIfAbsent(p, "enable.idempotence", "true");
        putIfAbsent(p, "retries", String.valueOf(Integer.MAX_VALUE));
        putIfAbsent(p, "delivery.timeout.ms", "120000");
        putIfAbsent(p, "request.timeout.ms", "30000");
        // if idempotence=true on modern brokers, >1 is fine
        putIfAbsent(p, "max.in.flight.requests.per.connection", "5");
        putIfAbsent(p, "compression.type", "snappy");
        putIfAbsent(p, "client.id", "frankenstein-publisher");
    }

    private static void hardenConsumerDefaults(Properties p) {
        putIfAbsent(p, "max.poll.interval.ms", "300000");
        putIfAbsent(p, "session.timeout.ms", "10000");
        putIfAbsent(p, "heartbeat.interval.ms", "3000");
        putIfAbsent(p, "client.id", "tradefrankenstein");
        // leave enable.auto.commit as-is; user decides
    }

    private static void putIfAbsent(Properties p, String key, String value) {
        if (!p.containsKey(key) || isBlank(p.getProperty(key))) {
            p.setProperty(key, value);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
