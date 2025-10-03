package com.trade.frankenstein.trader.ui.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * EngineApiClient
 * ----------------
 * Thin, Java‑8 friendly wrapper over the shared {@link ApiClient}.
 * Uses only the method names that exist in the uploaded ApiClient:
 * - ApiClient.get(String url, Class<T> type)
 * - ApiClient.get(String url, TypeReference<T> typeRef)
 * - ApiClient.post(String url, Object body, Class<T> type)
 * - ApiClient.readTree(String json)  (utility when needed)
 * <p>
 * All endpoints are addressed with relative paths so it works both from the
 * Vaadin UI app (via the API gateway) and directly against the service.
 */
public final class EngineApiClient {

    private static final String BASE = "/api/engine";

    // ===== Simple lifecycle ops =====

    /**
     * Returns a normalized textual status such as RUNNING, IDLE, or UNKNOWN.
     */
    public String status() {
        String s = ApiClient.get(BASE + "/status", String.class);
        if (s == null) return "UNKNOWN";
        s = s.trim();
        if (s.isEmpty()) return "UNKNOWN";
        String u = s.toUpperCase();
        if ("RUN".equals(u)) return "RUNNING";
        if ("STOP".equals(u)) return "IDLE";
        return u;
    }

    /**
     * Start the engine; returns a small JSON payload with status/ts.
     */
    public JsonNode start() {
        return ApiClient.post(BASE + "/start", null, JsonNode.class);
    }

    /**
     * Stop the engine gracefully; returns a small JSON payload with status/ts.
     */
    public JsonNode stop() {
        return ApiClient.post(BASE + "/stop", null, JsonNode.class);
    }

    /**
     * Force kill (if supported server‑side); returns a small JSON payload.
     */
    public JsonNode kill() {
        return ApiClient.post(BASE + "/kill", null, JsonNode.class);
    }

    /**
     * Restart the engine; convenience around stop+start if the API exposes it.
     */
    public JsonNode restart() {
        return ApiClient.post(BASE + "/restart", null, JsonNode.class);
    }

    // ===== Configuration =====

    /**
     * Fetch current effective configuration for display.
     */
    public JsonNode getConfig() {
        return ApiClient.get(BASE + "/config", JsonNode.class);
    }

    /**
     * Upsert/merge configuration (server decides exact semantics).
     */
    public JsonNode updateConfig(JsonNode configPatch) {
        return ApiClient.post(BASE + "/config", configPatch, JsonNode.class);
    }

    // ===== Telemetry =====

    /**
     * Engine metrics snapshot (counters, timings, etc.).
     */
    public JsonNode metrics() {
        return ApiClient.get(BASE + "/metrics", JsonNode.class);
    }

    /**
     * Recent audit events; if limit <= 0 server decides default.
     */
    public List<Map<String, Object>> audit(int limit) {
        String url = BASE + "/audit" + (limit > 0 ? ("?limit=" + limit) : "");
        return ApiClient.get(url, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    /**
     * Recent decisions list used by Regime/Decision cards.
     */
    public List<Map<String, Object>> recentDecisions(int limit) {
        String url = BASE + "/decisions/recent" + (limit > 0 ? ("?limit=" + limit) : "");
        return ApiClient.get(url, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    /**
     * Recent trades list if the engine exposes it under its namespace.
     */
    public List<Map<String, Object>> recentTrades(int limit) {
        String url = BASE + "/trades/recent" + (limit > 0 ? ("?limit=" + limit) : "");
        return ApiClient.get(url, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    /**
     * Health object (optional, but convenient for a single call from UI).
     */
    public JsonNode health() {
        return ApiClient.get(BASE + "/health", JsonNode.class);
    }
}
