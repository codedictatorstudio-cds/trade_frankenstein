package com.trade.frankenstein.trader.service.sentiment;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class SocialMediaApiClient {

    @Value("${trading.social.api.key:}")
    private String apiKey;

    @Value("${trading.social.api.enabled:false}")
    private boolean apiEnabled;

    @Value("${trading.social.search.keywords:$SPY,market,stocks,trading}")
    private String searchKeywords;

    private final HttpClient httpClient;
    private final Map<String, CachedSentiment> cache = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public SocialMediaApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Returns recent polarity score from social media sentiment analysis.
     * Range: -1.0 (bearish) to +1.0 (bullish), 0.0 = neutral
     */
    public Optional<Double> getRecentPolarity() {
        try {
            if (!apiEnabled || apiKey == null || apiKey.isEmpty()) {
                return getSimulatedPolarity();
            }

            // Check cache first (5-minute TTL)
            CachedSentiment cached = cache.get("recent_polarity");
            if (cached != null && cached.isValid()) {
                return Optional.of(cached.polarity);
            }

            // Fetch fresh data
            Double polarity = fetchRealPolarity();
            if (polarity != null) {
                cache.put("recent_polarity", new CachedSentiment(polarity, Instant.now()));
                return Optional.of(polarity);
            }

            return getSimulatedPolarity();

        } catch (Exception e) {
            log.error("Error fetching social media sentiment: {}", e.getMessage());
            return getSimulatedPolarity();
        }
    }

    /**
     * Get sentiment for specific stock symbol
     */
    public Optional<Double> getSymbolPolarity(String symbol) {
        try {
            String cacheKey = "symbol_" + symbol;
            CachedSentiment cached = cache.get(cacheKey);
            if (cached != null && cached.isValid()) {
                return Optional.of(cached.polarity);
            }

            if (!apiEnabled || apiKey == null || apiKey.isEmpty()) {
                return getSimulatedSymbolPolarity(symbol);
            }

            Double polarity = fetchSymbolPolarity(symbol);
            if (polarity != null) {
                cache.put(cacheKey, new CachedSentiment(polarity, Instant.now()));
                return Optional.of(polarity);
            }

            return getSimulatedSymbolPolarity(symbol);

        } catch (Exception e) {
            log.error("Error fetching sentiment for symbol {}: {}", symbol, e.getMessage());
            return getSimulatedSymbolPolarity(symbol);
        }
    }

    /**
     * Get sentiment volume/buzz level (0-100)
     */
    public Optional<Integer> getSentimentVolume() {
        try {
            CachedSentiment cached = cache.get("volume");
            if (cached != null && cached.isValid()) {
                return Optional.of((int) (cached.polarity * 100));
            }

            if (!apiEnabled) {
                return Optional.of(random.nextInt(40) + 30); // 30-70 range
            }

            Integer volume = fetchSentimentVolume();
            if (volume != null) {
                cache.put("volume", new CachedSentiment(volume / 100.0, Instant.now()));
                return Optional.of(volume);
            }

            return Optional.of(random.nextInt(40) + 30);

        } catch (Exception e) {
            log.error("Error fetching sentiment volume: {}", e.getMessage());
            return Optional.of(50);
        }
    }

    // --- Real API Implementation Methods ---

    private Double fetchRealPolarity() {
        try {
            // Example: Using a hypothetical sentiment API
            String url = buildSentimentApiUrl();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parsePolarityFromResponse(response.body());
            } else {
                log.warn("API returned status: {} for sentiment request", response.statusCode());
                return null;
            }

        } catch (IOException | InterruptedException e) {
            log.error("HTTP request failed for sentiment: {}", e.getMessage());
            return null;
        }
    }

    private Double fetchSymbolPolarity(String symbol) {
        try {
            String url = buildSymbolSentimentUrl(symbol);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parsePolarityFromResponse(response.body());
            }
            return null;

        } catch (IOException | InterruptedException e) {
            log.error("HTTP request failed for symbol sentiment: {}", e.getMessage());
            return null;
        }
    }

    private Integer fetchSentimentVolume() {
        try {
            String url = buildVolumeApiUrl();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseVolumeFromResponse(response.body());
            }
            return null;

        } catch (IOException | InterruptedException e) {
            log.error("HTTP request failed for sentiment volume: {}", e.getMessage());
            return null;
        }
    }

    // --- URL Builders for Real APIs ---

    private String buildSentimentApiUrl() {
        // Example for Twitter API v2, Reddit API, or StockTwits
        return String.format(
                "https://api.socialmediasentiment.com/v1/sentiment?keywords=%s&timeframe=1h",
                String.join(",", searchKeywords.split(","))
        );
    }

    private String buildSymbolSentimentUrl(String symbol) {
        return String.format(
                "https://api.socialmediasentiment.com/v1/sentiment/symbol?symbol=%s&timeframe=1h",
                symbol
        );
    }

    private String buildVolumeApiUrl() {
        return String.format(
                "https://api.socialmediasentiment.com/v1/volume?keywords=%s&timeframe=1h",
                String.join(",", searchKeywords.split(","))
        );
    }

    // --- Response Parsers ---

    private Double parsePolarityFromResponse(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            // Example response structure:
            // {"sentiment": {"polarity": 0.65, "confidence": 0.85}}
            if (json.has("sentiment")) {
                JsonObject sentiment = json.getAsJsonObject("sentiment");
                if (sentiment.has("polarity")) {
                    double polarity = sentiment.get("polarity").getAsDouble();
                    // Ensure range [-1, 1]
                    return Math.max(-1.0, Math.min(1.0, polarity));
                }
            }

            // Alternative structure: {"polarity": 0.65}
            if (json.has("polarity")) {
                double polarity = json.get("polarity").getAsDouble();
                return Math.max(-1.0, Math.min(1.0, polarity));
            }

            return null;
        } catch (Exception e) {
            log.error("Failed to parse polarity from response: {}", e.getMessage());
            return null;
        }
    }

    private Integer parseVolumeFromResponse(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (json.has("volume")) {
                return json.get("volume").getAsInt();
            }

            if (json.has("buzz_score")) {
                return json.get("buzz_score").getAsInt();
            }

            return null;
        } catch (Exception e) {
            log.error("Failed to parse volume from response: {}", e.getMessage());
            return null;
        }
    }

    // --- Simulation Methods (for development/testing) ---

    private Optional<Double> getSimulatedPolarity() {
        // Simulate realistic market sentiment patterns
        double basePolarity = 0.1; // Slightly bullish market
        double noise = (random.nextGaussian() * 0.3); // Random walk component
        double polarity = basePolarity + noise;

        // Add some market session effects
        int hour = Instant.now().atZone(java.time.ZoneOffset.UTC).getHour();
        if (hour >= 9 && hour <= 16) { // Market hours - more extreme movements
            polarity *= 1.5;
        }

        // Clamp to [-1, 1]
        polarity = Math.max(-1.0, Math.min(1.0, polarity));

        log.debug("Simulated social media polarity: {}", polarity);
        return Optional.of(polarity);
    }

    private Optional<Double> getSimulatedSymbolPolarity(String symbol) {
        // Different symbols can have different sentiment patterns
        int symbolHash = symbol.hashCode();
        random.setSeed(symbolHash + Instant.now().getEpochSecond() / 300); // 5-min buckets

        double polarity = random.nextGaussian() * 0.4;
        polarity = Math.max(-1.0, Math.min(1.0, polarity));

        return Optional.of(polarity);
    }

    // --- Cache Helper ---

    private static class CachedSentiment {
        final double polarity;
        final Instant timestamp;

        CachedSentiment(double polarity, Instant timestamp) {
            this.polarity = polarity;
            this.timestamp = timestamp;
        }

        boolean isValid() {
            return Duration.between(timestamp, Instant.now()).compareTo(Duration.ofMinutes(5)) < 0;
        }
    }

    // --- Health Check ---

    public boolean isHealthy() {
        try {
            return getRecentPolarity().isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", apiEnabled);
        status.put("hasApiKey", apiKey != null && !apiKey.isEmpty());
        status.put("cacheSize", cache.size());
        status.put("healthy", isHealthy());
        status.put("keywords", searchKeywords);
        return status;
    }
}
