package com.trade.frankenstein.trader.service.decision;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trade.frankenstein.trader.bus.EventBusConfig;
import com.trade.frankenstein.trader.bus.EventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * DecisionEventSupport
 * ------------------------------------------------------------
 * Kafkaesque-compliant emitter for Decision-related events.
 * - Single flat topic: EventBusConfig.TOPIC_DECISION
 * - Semantic event name inside payload (e.g., "decision.quality")
 * - Java 8 only, no reflection, no Object usage
 * - Uses Gson JsonObject (not StringBuilder)
 *
 * Usage inside DecisionService (examples):
 *   new DecisionEventSupport(eventPublisher)
 *        .base("decision.quality")
 *        .id(decision.getId())
 *        .symbol(decision.getSymbol())
 *        .instrumentToken(decision.getInstrument_token())
 *        .score(decision.getQualityScore())
 *        .status(decision.getStatus() == null ? null : decision.getStatus().name())
 *        .rationale(decision.getRationale())
 *        .reasons(decision.getReasons()) // List<String> if available
 *        .emit();
 *
 * For keying, we prefer: symbol -> instrumentToken -> id.
 */
@Service
public class DecisionEventSupport {

    @Autowired
    private final EventPublisher publisher;

    // core fields
    private String event;
    private String id;
    private String symbol;
    private String instrumentToken;
    private Double score;
    private String status;
    private String rationale;
    private List<String> reasons;
    private String strategy;
    private String regime;
    private Double confidence;

    public DecisionEventSupport(EventPublisher publisher) {
        this.publisher = publisher;
    }

    public DecisionEventSupport base(String event) {
        this.event = event;
        return this;
    }

    public DecisionEventSupport id(String id) {
        this.id = id;
        return this;
    }

    public DecisionEventSupport symbol(String symbol) {
        this.symbol = symbol;
        return this;
    }

    public DecisionEventSupport instrumentToken(String instrumentToken) {
        this.instrumentToken = instrumentToken;
        return this;
    }

    public DecisionEventSupport score(Double score) {
        this.score = score;
        return this;
    }

    public DecisionEventSupport status(String status) {
        this.status = status;
        return this;
    }

    public DecisionEventSupport rationale(String rationale) {
        this.rationale = rationale;
        return this;
    }

    public DecisionEventSupport reasons(List<String> reasons) {
        this.reasons = reasons;
        return this;
    }

    public DecisionEventSupport strategy(String strategy) {
        this.strategy = strategy;
        return this;
    }

    public DecisionEventSupport regime(String regime) {
        this.regime = regime;
        return this;
    }

    public DecisionEventSupport confidence(Double confidence) {
        this.confidence = confidence;
        return this;
    }

    public void emit() {
        if (publisher == null) return;

        Instant now = Instant.now();
        JsonObject o = new JsonObject();
        o.addProperty("ts", now.toEpochMilli());
        o.addProperty("ts_iso", now.toString());
        o.addProperty("event", event == null ? "decision.unknown" : event);
        o.addProperty("source", "decision");

        if (id != null && !id.trim().isEmpty()) o.addProperty("id", id);
        if (symbol != null && !symbol.trim().isEmpty()) o.addProperty("symbol", symbol);
        if (instrumentToken != null && !instrumentToken.trim().isEmpty()) o.addProperty("instrument_token", instrumentToken);
        if (status != null && !status.trim().isEmpty()) o.addProperty("status", status);
        if (rationale != null && !rationale.trim().isEmpty()) o.addProperty("rationale", rationale);
        if (strategy != null && !strategy.trim().isEmpty()) o.addProperty("strategy", strategy);
        if (regime != null && !regime.trim().isEmpty()) o.addProperty("regime", regime);
        if (score != null) o.addProperty("score", score);
        if (confidence != null) o.addProperty("confidence", confidence);

        if (reasons != null && !reasons.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String r : reasons) {
                if (r != null && !r.trim().isEmpty()) arr.add(r);
            }
            if (arr.size() > 0) o.add("reasons", arr);
        }

        // best-effort key selection
        String key = firstNonBlank(symbol, instrumentToken, id);
        publisher.publish(EventBusConfig.TOPIC_DECISION, isBlank(key) ? null : key, o.toString());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String firstNonBlank(String a, String b, String c) {
        if (!isBlank(a)) return a;
        if (!isBlank(b)) return b;
        if (!isBlank(c)) return c;
        return null;
    }

    /* --------- Static helpers for common events ---------- */

    public static String EVT_NEW()       { return "decision.new"; }
    public static String EVT_QUALITY()   { return "decision.quality"; }
    public static String EVT_UPDATED()   { return "decision.updated"; }
    public static String EVT_DISMISSED() { return "decision.dismissed"; }
    public static String EVT_BLOCKED()   { return "decision.blocked"; }
}
