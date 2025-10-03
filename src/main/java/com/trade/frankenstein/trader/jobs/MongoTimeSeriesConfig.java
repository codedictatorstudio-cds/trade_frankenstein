package com.trade.frankenstein.trader.jobs;

import com.mongodb.client.MongoDatabase;
import lombok.Data;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.HashSet;
import java.util.Set;

/**
 * MongoDB time-series bootstrap for ticks & candles.
 * <p>
 * Creates time-series collections with:
 * - timeField (ts / openTime)
 * - metaField "symbol"
 * - granularity (minutes)
 * - expireAfterSeconds (retention)
 * And adds compound index: {symbol:1, timeField:1}.
 * <p>
 * Toggle with:
 * trade.mongo.timeseries.enabled=true
 * trade.mongo.timeseries.init=true
 * <p>
 * Properties (defaults shown below) can be overridden in application.properties.
 */
@Configuration
@ConditionalOnProperty(prefix = "trade.mongo.timeseries", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MongoTimeSeriesConfig {

    private static final Logger log = LoggerFactory.getLogger(MongoTimeSeriesConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "trade.mongo.timeseries")
    public MongoTsProps mongoTsProps() {
        return new MongoTsProps();
    }

    @Bean
    public ApplicationRunner mongoTsBootstrap(MongoTemplate mongoTemplate, MongoTsProps p) {
        return args -> {
            if (!p.isInit()) {
                log.info("Mongo time-series bootstrap disabled (trade.mongo.timeseries.init=false). Skipping.");
                return;
            }

            MongoDatabase db = mongoTemplate.getDb();
            Set<String> existing = new HashSet<>();
            db.listCollectionNames().into(new java.util.ArrayList<>()).forEach(existing::add);

            // Ticks
            ensureTimeSeriesCollection(
                    db,
                    mongoTemplate,
                    existing,
                    p.getTickCollection(),
                    p.getTickTimeField(),
                    p.getGranularity(),
                    p.getTickExpireAfterSeconds()
            );

            // Candles (align with @Document(\"candles_1m\"))
            ensureTimeSeriesCollection(
                    db,
                    mongoTemplate,
                    existing,
                    p.getCandleCollection(),
                    p.getCandleTimeField(),
                    p.getGranularity(),
                    p.getCandleExpireAfterSeconds()
            );

            log.info("Mongo time-series bootstrap complete.");
        };
    }

    private void ensureTimeSeriesCollection(
            MongoDatabase db,
            MongoTemplate template,
            Set<String> existing,
            String collection,
            String timeField,
            String granularity,
            long expireAfterSeconds
    ) {
        if (!existing.contains(collection)) {
            // Build the "create" command with time-series options
            Document cmd = new Document("create", collection)
                    .append("timeseries", new Document("timeField", timeField)
                            .append("metaField", "symbol")
                            .append("granularity", granularity))
                    .append("expireAfterSeconds", expireAfterSeconds);

            try {
                log.info("Creating time-series collection '{}' (timeField='{}', granularity='{}', expireAfterSeconds={})",
                        collection, timeField, granularity, expireAfterSeconds);
                db.runCommand(cmd);
            } catch (Throwable t) {
                log.warn("Failed to create time-series collection '{}': {}. " +
                                "If the collection already exists as a normal collection, drop it (with caution) and rerun, or create manually.",
                        collection, t.getMessage());
            }
        } else {
            log.info("Collection '{}' already exists — skipping create.", collection);
        }

        // Ensure compound index: {symbol:1, timeField:1}
        try {
            Document keys = new Document("symbol", 1).append(timeField, 1);
            boolean exists = template.getCollection(collection)
                    .listIndexes()
                    .into(new java.util.ArrayList<>())
                    .stream()
                    .anyMatch(ix -> keys.toJson().equals(String.valueOf(ix.get("key"))));
            if (!exists) {
                log.info("Creating compound index on '{}': {}", collection, keys.toJson());
                template.getCollection(collection).createIndex(keys); // let Mongo assign a name
            } else {
                log.info("Index already present on '{}': {}", collection, keys.toJson());
            }
        } catch (Throwable t) {
            log.warn("Failed to ensure index on '{}': {}", collection, t.getMessage());
        }
    }

    /**
     * Externalized knobs for collection names, fields, granularity & retention.
     * Defaults aligned to your @Document names: ticks / candles_1m.
     */
    @Data
    public static class MongoTsProps {
        /**
         * Run bootstrap at startup
         */
        private boolean init = false;

        /**
         * Collections & time fields
         */
        private String tickCollection = "ticks";
        private String tickTimeField = "ts";

        // CHANGED: default to candles_1m to match your @Document("candles_1m")
        private String candleCollection = "candles_1m";
        private String candleTimeField = "openTime";

        /**
         * Granularity: "seconds" | "minutes" | "hours" (MongoDB)
         */
        private String granularity = "minutes";

        /**
         * Retention windows (seconds) — adjust to taste
         */
        private long tickExpireAfterSeconds = 30L * 24 * 60 * 60;     // 30 days
        private long candleExpireAfterSeconds = 365L * 24 * 60 * 60;  // 365 days
    }
}
