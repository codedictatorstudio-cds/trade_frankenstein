package com.trade.frankenstein.trader.ui.header;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.ui.bridge.ApiClient;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.FlexLayout;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * ControlsBar — Simplified mode toggle + engine controls + decision quality display.
 * <p>
 * Endpoints used:
 * - GET  /api/decision/quality  → DecisionQuality { score:int, trend:"BULLISH|BEARISH|NEUTRAL", ... }
 * - POST /api/engine/start|stop|kill
 */
public final class ControlsBar extends FlexLayout {

    // ---- REST paths ----
    private static final String ENGINE_BASE = "/api/engine";
    private static final String ENGINE_START = ENGINE_BASE + "/start";
    private static final String ENGINE_STOP = ENGINE_BASE + "/stop";
    private static final String DECISION_QUALITY = "/api/decision/quality";

    // ---- UI Components ----
    private final Button startBtn = createControlButton("Start Engine", VaadinIcon.PLAY, "success");
    private final Button stopBtn = createControlButton("Stop Engine", VaadinIcon.STOP, "error");

    // Confidence components
    private final Div confidenceContainer = new Div();
    private final Div confidenceProgress = new Div();
    private final Span confidenceValue = new Span("50%");
    private final Span confidenceLabel = new Span("Decision Confidence");

    // Engine status and trend display
    private final Span engineStatus = new Span("UNKNOWN");
    private final Span trendChip = new Span();
    private final Icon trendIcon = new Icon();
    private final Div statusIndicator = new Div();

    // ---- State ----
    private volatile int decisionScore = 50;      // 0..100
    private volatile String decisionTrend = "NEUTRAL";

    // ---- Infra ----
    private final ObjectMapper M = new ObjectMapper();
    private ScheduledExecutorService poller;
    private static final Duration POLL_EVERY = Duration.ofSeconds(5);

    public ControlsBar() {
        // Clean container styling
        addClassName("tf-controls-bar");
        getStyle()
                .set("background", "white")
                .set("border-bottom", "1px solid var(--tf-border)")
                .set("padding", "12px 20px")
                .set("box-shadow", "0 2px 4px rgba(0, 0, 0, 0.05)")
                .set("z-index", "10");

        setWidthFull();
        setAlignItems(FlexComponent.Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.BETWEEN);

        // Create horizontal layout sections
        Div leftSection = createModeSection();
        Div centerSection = createEngineControlsSection();
        Div rightSection = createConfidenceSection();

        // Simple engine status styling
        engineStatus.getElement().getThemeList().add("badge");
        engineStatus.getStyle()
                .set("text-transform", "uppercase")
                .set("font-weight", "600")
                .set("padding", "6px 12px")
                .set("border-radius", "16px")
                .set("font-size", "12px");

        add(engineStatus);
        add(leftSection, centerSection, rightSection);

        // Initial state
        updateTrend("NEUTRAL");
        updateConfidenceScore(50);
    }

    private Div createModeSection() {
        // Create mode section header
        Span modeTitle = new Span("Trading Environment");
        modeTitle.getStyle()
                .set("font-size", "12px")
                .set("font-weight", "600")
                .set("color", "var(--tf-text-muted)")
                .set("display", "block")
                .set("margin-bottom", "8px");

        Div modeSection = new Div(modeTitle);
        modeSection.addClassName("mode-section");
        modeSection.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "flex-start")
                .set("min-width", "180px")
                .set("padding", "0 12px");

        return modeSection;
    }

    private Div createEngineControlsSection() {
        // Engine button actions
        startBtn.addClickListener(e -> callEngineCommand(ENGINE_START));
        stopBtn.addClickListener(e -> callEngineCommand(ENGINE_STOP));

        // Simple button group
        Div buttonGroup = new Div(startBtn, stopBtn);
        buttonGroup.addClassName("button-group");
        buttonGroup.getStyle()
                .set("display", "flex")
                .set("gap", "12px")
                .set("align-items", "center");

        // Simple engine controls section
        Span engineTitle = new Span("Engine Control");
        engineTitle.getStyle()
                .set("font-size", "12px")
                .set("font-weight", "600")
                .set("color", "var(--tf-text-muted)")
                .set("display", "block")
                .set("margin-bottom", "10px");

        Div engineSection = new Div(engineTitle, buttonGroup);
        engineSection.addClassName("engine-controls-section");
        engineSection.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "center")
                .set("flex", "1")
                .set("justify-content", "center")
                .set("padding", "0 20px");

        return engineSection;
    }

    private Div createConfidenceSection() {
        // Setup confidence meter
        setupConfidenceMeter();
        setupTrendDisplay();

        // Create confidence layout
        Div confidenceLayout = new Div(createConfidenceMeter(), createTrendContainer());
        confidenceLayout.addClassName("confidence-layout");
        confidenceLayout.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("gap", "20px");

        // Create confidence section header
        Span confidenceTitle = new Span("Market Intelligence");
        confidenceTitle.getStyle()
                .set("font-size", "12px")
                .set("font-weight", "600")
                .set("color", "var(--tf-text-muted)")
                .set("display", "block")
                .set("margin-bottom", "10px")
                .set("text-align", "center");

        Div rightSection = new Div(confidenceTitle, confidenceLayout);
        rightSection.addClassName("confidence-section");
        rightSection.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "flex-end")
                .set("min-width", "360px");

        return rightSection;
    }

    private void setupTrendDisplay() {
        trendIcon.getStyle()
                .set("margin-right", "8px")
                .set("font-size", "16px");

        statusIndicator.addClassName("status-indicator");
        statusIndicator.getStyle()
                .set("width", "8px")
                .set("height", "8px")
                .set("border-radius", "50%")
                .set("margin-left", "8px");
    }

    private void setupConfidenceMeter() {
        // Simple confidence meter styling
        confidenceContainer.addClassName("confidence-container");
        confidenceContainer.getStyle()
                .set("position", "relative")
                .set("width", "200px")
                .set("height", "8px")
                .set("background-color", "var(--tf-border)")
                .set("border-radius", "4px")
                .set("overflow", "hidden");

        confidenceProgress.addClassName("confidence-progress");
        confidenceProgress.getStyle()
                .set("position", "absolute")
                .set("height", "100%")
                .set("width", "50%")
                .set("background-color", "var(--tf-accent)")
                .set("border-radius", "4px")
                .set("transition", "width 0.5s ease");

        confidenceValue.getStyle()
                .set("font-size", "14px")
                .set("font-weight", "600")
                .set("margin-bottom", "4px");

        confidenceLabel.getStyle()
                .set("font-size", "11px")
                .set("font-weight", "500")
                .set("color", "var(--tf-text-muted)");
    }

    private Div createConfidenceMeter() {
        // Create simple vertical layout for the confidence meter
        Div valueAndLabel = new Div(confidenceValue, confidenceLabel);
        valueAndLabel.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("margin-bottom", "6px");

        // Add progress bar beneath the values
        confidenceContainer.add(confidenceProgress);

        Div meterContainer = new Div(valueAndLabel, confidenceContainer);
        meterContainer.addClassName("confidence-meter");
        meterContainer.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("min-width", "200px");

        return meterContainer;
    }

    private Div createTrendContainer() {
        Div trendContainer = new Div(trendIcon, trendChip, statusIndicator);
        trendContainer.addClassName("trend-container");
        trendContainer.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("padding", "6px 12px")
                .set("border", "1px solid var(--tf-border)")
                .set("border-radius", "16px")
                .set("justify-content", "center");

        return trendContainer;
    }

    // ---- Lifecycle ----
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        final UI ui = attachEvent.getUI();
        safeUi(ui, this::refreshAll);

        if (poller == null) {
            poller = new ScheduledThreadPoolExecutor(1);
            ((ScheduledThreadPoolExecutor) poller).setRemoveOnCancelPolicy(true);
            poller.scheduleWithFixedDelay(() -> {
                try {
                    DecisionSnapshot snap = fetchDecisionQuality();
                    ui.access(() -> {
                        updateConfidenceScore(snap.score);
                        updateTrend(snap.trend);
                    });
                } catch (Throwable ignore) { /* best-effort */ }
            }, 0, POLL_EVERY.getSeconds(), TimeUnit.SECONDS);
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (poller != null) {
            poller.shutdownNow();
            poller = null;
        }
    }

    // ---- Refresh helpers ----
    private void refreshAll() {
        try {
            DecisionSnapshot snap = fetchDecisionQuality();
            updateConfidenceScore(snap.score);
            updateTrend(snap.trend);
        } catch (Throwable ignored) { /* best-effort */ }
    }

    /**
     * Reads DecisionQuality from DecisionController and extracts {score, trend}.
     * Expected shape (from DecisionQuality.java): { "score": int, "trend": "BULLISH|BEARISH|NEUTRAL", ... }
     * Also tolerates wrappers like { "ok":true, "data":{...} } or { "value":{...} }.
     */
    private DecisionSnapshot fetchDecisionQuality() {
        JsonNode resp = null;
        try {
            resp = ApiClient.get(DECISION_QUALITY, JsonNode.class);
        } catch (Throwable ignored) { /* endpoint missing or error */ }

        if (resp == null) return new DecisionSnapshot(decisionScore, decisionTrend);

        JsonNode payload = resp;
        if (payload.has("data") && payload.get("data").isObject()) payload = payload.get("data");
        else if (payload.has("value") && payload.get("value").isObject()) payload = payload.get("value");

        int s = payload.path("score").asInt(Integer.MIN_VALUE);
        String t = payload.path("trend").asText(null);

        if (s == Integer.MIN_VALUE) s = decisionScore;
        if (t == null) t = decisionTrend;

        return new DecisionSnapshot(clamp0to100(s), t);
    }

    // ---- Engine commands with feedback ----
    private void callEngineCommand(String path) {
        try {
            // Ask for String so we can handle BOTH JSON and plain text replies.
            String raw = ApiClient.post(path, M.createObjectNode(), String.class);
            boolean ok = isEngineOk(raw);
            showNotification(
                    ok ? "Command executed successfully" : "Command failed",
                    ok ? NotificationVariant.LUMO_SUCCESS : NotificationVariant.LUMO_ERROR
            );

            // (Optional) reflect engine state in UI immediately
            if (ok) {
                String state = extractEngineState(raw); // "started"/"running"/"stopped"/"ok"/"-"
                if (!state.isEmpty()) {
                    updateEngineStatus(state.toUpperCase(Locale.ROOT)); // e.g., "STARTED"
                }
            }
        } catch (Throwable t) {
            showNotification("Command execution error", NotificationVariant.LUMO_ERROR);
        }
    }

    private void updateEngineStatus(String state) {
        if (state == null) state = "UNKNOWN";
        final String s = state.trim().isEmpty() ? "UNKNOWN" : state.trim().toUpperCase();

        // text
        engineStatus.setText(s);

        // color variant
        var tl = engineStatus.getElement().getThemeList();
        tl.remove("success");
        tl.remove("error");
        tl.remove("contrast");

        switch (s) {
            case "STARTED":
            case "RUNNING":
            case "ON":
            case "OK":
                tl.add("success");
                break;
            case "STOPPED":
            case "OFF":
            case "FAILED":
            case "ERROR":
                tl.add("error");
                break;
            default:
                tl.add("contrast");
        }
    }

    private boolean isEngineOk(String raw) {
        if (raw == null) return false;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return false;

        // plain-text quick wins
        if ("ok".equals(s)) return true;
        if (s.contains("engine:started") || s.contains("engine:stopped")) return true;

        // JSON-friendly checks
        try {
            JsonNode n = M.readTree(raw);
            if (n.path("ok").asBoolean(false)) return true;
            String eng = n.path("engine").asText("");
            if ("started".equalsIgnoreCase(eng) || "stopped".equalsIgnoreCase(eng)) return true;
            String status = n.path("status").asText("");
            return "ok".equalsIgnoreCase(status);
        } catch (Exception ignore) {
            // not JSON, handled by plain-text checks above
        }
        return false;
    }

    private String extractEngineState(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        try {
            JsonNode n = M.readTree(s);
            if (n.has("engine")) return n.get("engine").asText("");
            if (n.has("status")) return n.get("status").asText("");
            if (n.has("ok") && n.get("ok").asBoolean(false)) return "ok";
        } catch (Exception ignore) {
            // plain text like "engine:started"
            int i = s.toLowerCase(Locale.ROOT).indexOf("engine:");
            if (i >= 0 && i + 7 < s.length()) return s.substring(i + 7).trim();
            if ("ok".equalsIgnoreCase(s)) return "ok";
        }
        return "";
    }

    // ---- Trend handling & confidence helpers ----
    private void updateTrend(String trend) {
        if (trend == null || trend.isEmpty()) trend = "NEUTRAL";
        trendChip.setText(trend);
        this.decisionTrend = trend;

        // Simple styling based on trend
        switch (trend.toUpperCase(Locale.ROOT)) {
            case "BULLISH":
                trendIcon.setIcon(VaadinIcon.ARROW_UP);
                trendChip.getStyle().set("color", "#10b981");
                statusIndicator.getStyle().set("background-color", "#10b981");
                break;
            case "BEARISH":
                trendIcon.setIcon(VaadinIcon.ARROW_DOWN);
                trendChip.getStyle().set("color", "#ef4444");
                statusIndicator.getStyle().set("background-color", "#ef4444");
                break;
            case "NEUTRAL":
            default:
                trendIcon.setIcon(VaadinIcon.MINUS);
                trendChip.getStyle().set("color", "#6b7280");
                statusIndicator.getStyle().set("background-color", "#6b7280");
                break;
        }
    }

    private void updateConfidenceScore(int score) {
        int validScore = clamp0to100(score);
        this.decisionScore = validScore;

        // Update the text display
        confidenceValue.setText(validScore + "%");

        // Update the progress bar
        confidenceProgress.getStyle().set("width", validScore + "%");

        // Set color based on score range
        String color;
        if (validScore >= 70) {
            color = "#10b981"; // green for high confidence
        } else if (validScore >= 40) {
            color = "#f59e0b"; // amber for medium confidence
        } else {
            color = "#ef4444"; // red for low confidence
        }
        confidenceProgress.getStyle().set("background-color", color);
    }

    private static int clamp0to100(int val) {
        return Math.max(0, Math.min(100, val));
    }

    // ---- UI helpers ----
    private Button createControlButton(String text, VaadinIcon icon, String theme) {
        Button btn = new Button(text, new Icon(icon));
        btn.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        if (theme != null && !theme.isEmpty()) {
            btn.addThemeVariants(ButtonVariant.valueOf("LUMO_" + theme.toUpperCase()));
        }
        return btn;
    }

    private static Span createPremiumPill() {
        Span chip = new Span();
        chip.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("font-weight", "600")
                .set("font-size", "12px")
                .set("padding", "0 8px")
                .set("white-space", "nowrap");
        return chip;
    }

    private void showNotification(String message, NotificationVariant variant) {
        Notification notification = new Notification();
        notification.addThemeVariants(variant);
        notification.setText(message);
        notification.setDuration(3000);
        notification.setPosition(Notification.Position.TOP_END);
        notification.open();
    }

    private static void safeUi(UI ui, Runnable action) {
        if (ui != null) {
            try {
                ui.access(action::run);
            } catch (Throwable ignore) { /* UI gone or action failed */ }
        }
    }

    // ---- Data container ----
        private record DecisionSnapshot(int score, String trend) {
    }

    private void animateAction(Runnable action) {
        action.run();
    }
}

