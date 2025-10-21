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
 * ControlsBar — Premium, clean design with no section headings.
 * Only relevant components displayed. Complete space usage.
 * Endpoints used:
 * - GET /api/decision/quality → DecisionQuality { score:int, trend:"BULLISH|BEARISH|NEUTRAL", ... }
 * - POST /api/engine/start|stop|kill
 */
public final class ControlsBar extends FlexLayout {

    // ---- REST paths ----
    private static final String ENGINE_BASE = "/api/engine";
    private static final String ENGINE_START = ENGINE_BASE + "/start";
    private static final String ENGINE_STOP = ENGINE_BASE + "/stop";
    private static final String DECISION_QUALITY = "/api/decision/quality";

    // ---- UI Components ----
    private final Button startBtn = createControlButton("Start", VaadinIcon.PLAY, "success");
    private final Button stopBtn = createControlButton("Stop", VaadinIcon.STOP, "error");
    private final Div confidenceContainer = new Div();
    private final Div confidenceProgress = new Div();
    private final Span confidenceValue = new Span("50%");
    private final Span confidenceLabel = new Span("Confidence");
    private final Span engineStatus = new Span("UNKNOWN");
    private final Span trendChip = new Span();
    private final Icon trendIcon = new Icon();
    private final Div statusIndicator = new Div();

    // ---- State ----
    private volatile int decisionScore = 50;
    private volatile String decisionTrend = "NEUTRAL";

    // ---- Infra ----
    private final ObjectMapper M = new ObjectMapper();
    private ScheduledExecutorService poller;
    private static final Duration POLL_EVERY = Duration.ofSeconds(5);

    public ControlsBar() {
        addClassName("tf-controls-bar");
        getStyle()
                .set("background", "linear-gradient(135deg, #ffffff 0%, #f8fafc 100%)")
                .set("padding", "0")
                .set("border-radius", "12px")
                .set("box-shadow", "0 1px 8px rgba(0,0,0,0.04)")
                .set("border", "1px solid rgba(226,232,240,0.8)");
        setWidthFull();
        setHeight("auto");
        setAlignItems(FlexComponent.Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.BETWEEN);

        // Engine status indicator - will be placed in center section
        engineStatus.getElement().getThemeList().add("badge");
        engineStatus.getStyle()
                .set("text-transform", "uppercase")
                .set("font-weight", "700")
                .set("padding", "4px 12px")
                .set("font-size", "12px")
                .set("letter-spacing", "0.5px")
                .set("text-align", "center")
                .set("color", "#1e293b")
                .set("background", "rgba(241,245,249,0.5)")
                .set("border-radius", "6px")
                .set("margin-bottom", "8px");

        // Main sections - compact and aligned
        Div leftSection = createModeSection();
        Div centerSection = createEngineControlsSection();
        Div rightSection = createConfidenceSection();

        // Unified section styling for premium look
        String sectionPadding = "12px 16px";
        String sectionBorderRadius = "8px";

        leftSection.getStyle()
                .set("background", "transparent")
                .set("flex", "1")
                .set("margin", "8px 6px 8px 8px")
                .set("border-radius", sectionBorderRadius)
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("justify-content", "center")
                .set("align-items", "center")
                .set("padding", sectionPadding)
                .set("min-height", "60px");

        centerSection.getStyle()
                .set("background", "transparent")
                .set("flex", "1")
                .set("margin", "8px 6px")
                .set("border-radius", sectionBorderRadius)
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("justify-content", "center")
                .set("align-items", "center")
                .set("padding", sectionPadding)
                .set("min-height", "60px")
                .set("border-left", "1px solid rgba(226,232,240,0.5)")
                .set("border-right", "1px solid rgba(226,232,240,0.5)");

        rightSection.getStyle()
                .set("background", "transparent")
                .set("flex", "1")
                .set("margin", "8px 8px 8px 6px")
                .set("border-radius", sectionBorderRadius)
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("justify-content", "center")
                .set("align-items", "center")
                .set("padding", sectionPadding)
                .set("min-height", "60px");

        leftSection.setWidthFull();
        centerSection.setWidthFull();
        rightSection.setWidthFull();

        setFlexGrow(1, leftSection, centerSection, rightSection);

        add(leftSection, centerSection, rightSection);

        updateTrend("NEUTRAL");
        updateConfidenceScore(50);
    }

    private Div createModeSection() {
        // Trade Frankenstein branding
        Span brandingText = new Span("Trade Frankenstein");
        brandingText.getStyle()
                .set("font-size", "16px")
                .set("font-weight", "700")
                .set("color", "#1e40af")
                .set("letter-spacing", "0.3px");

        Div modeSection = new Div(brandingText);
        modeSection.addClassName("mode-section");
        return modeSection;
    }

    private Div createEngineControlsSection() {
        startBtn.addClassName("engine-start-btn");
        stopBtn.addClassName("engine-stop-btn");
        startBtn.addClickListener(e -> callEngineCommand(ENGINE_START));
        stopBtn.addClickListener(e -> callEngineCommand(ENGINE_STOP));

        startBtn.getStyle()
                .set("font-size", "13px")
                .set("font-weight", "600")
                .set("padding", "8px 20px")
                .set("border-radius", "8px")
                .set("min-width", "90px");
        stopBtn.getStyle()
                .set("font-size", "13px")
                .set("font-weight", "600")
                .set("padding", "8px 20px")
                .set("border-radius", "8px")
                .set("min-width", "90px");

        Div buttonGroup = new Div(startBtn, stopBtn);
        buttonGroup.addClassName("button-group");
        buttonGroup.getStyle()
                .set("display", "flex")
                .set("gap", "12px")
                .set("align-items", "center")
                .set("justify-content", "center");

        Div engineSection = new Div(engineStatus, buttonGroup);
        engineSection.addClassName("engine-controls-section");
        engineSection.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "center")
                .set("gap", "8px");
        return engineSection;
    }

    private Div createConfidenceSection() {
        setupConfidenceMeter();
        setupTrendDisplay();

        Div confidenceLayout = new Div(createConfidenceMeter(), createTrendContainer());
        confidenceLayout.addClassName("confidence-layout");
        confidenceLayout.getStyle()
                .set("display", "flex")
                .set("flex-direction", "row")
                .set("align-items", "center")
                .set("gap", "16px")
                .set("justify-content", "center");

        Div rightSection = new Div(confidenceLayout);
        rightSection.addClassName("confidence-section");
        return rightSection;
    }

    private void setupTrendDisplay() {
        trendIcon.getStyle()
                .set("margin-right", "6px")
                .set("font-size", "14px");
        statusIndicator.addClassName("status-indicator");
        statusIndicator.getStyle()
                .set("width", "6px")
                .set("height", "6px")
                .set("border-radius", "50%")
                .set("margin-left", "8px");
        trendChip.getStyle()
                .set("font-weight", "600")
                .set("font-size", "12px")
                .set("letter-spacing", "0.3px");
    }

    private void setupConfidenceMeter() {
        confidenceContainer.addClassName("confidence-container");
        confidenceContainer.getStyle()
                .set("position", "relative")
                .set("width", "120px")
                .set("height", "6px")
                .set("background-color", "rgba(226,232,240,0.6)")
                .set("border-radius", "3px")
                .set("overflow", "hidden");

        confidenceProgress.addClassName("confidence-progress");
        confidenceProgress.getStyle()
                .set("position", "absolute")
                .set("height", "100%")
                .set("width", "50%")
                .set("background-color", "#3b82f6")
                .set("border-radius", "3px")
                .set("transition", "width 0.5s cubic-bezier(0.25, 0.8, 0.25, 1)");

        confidenceValue.getStyle()
                .set("font-size", "13px")
                .set("font-weight", "700")
                .set("color", "#1e293b")
                .set("min-width", "40px")
                .set("text-align", "right");

        confidenceLabel.getStyle()
                .set("font-size", "11px")
                .set("font-weight", "500")
                .set("color", "#94a3b8")
                .set("margin-bottom", "4px")
                .set("text-align", "center");
    }

    private Div createConfidenceMeter() {
        Div labelAndBar = new Div(confidenceLabel, confidenceContainer);
        labelAndBar.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "stretch")
                .set("gap", "4px")
                .set("min-width", "120px");

        confidenceContainer.add(confidenceProgress);

        Div meterContainer = new Div(labelAndBar, confidenceValue);
        meterContainer.addClassName("confidence-meter");
        meterContainer.getStyle()
                .set("display", "flex")
                .set("flex-direction", "row")
                .set("align-items", "center")
                .set("gap", "10px");
        return meterContainer;
    }

    private Div createTrendContainer() {
        Div trendContainer = new Div(trendIcon, trendChip, statusIndicator);
        trendContainer.addClassName("trend-container");
        trendContainer.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("padding", "6px 14px")
                .set("border", "1px solid rgba(226,232,240,0.7)")
                .set("border-radius", "16px")
                .set("background", "rgba(248,250,252,0.5)")
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
                } catch (Throwable ignore) {}
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
        } catch (Throwable ignored) {}
    }

    private DecisionSnapshot fetchDecisionQuality() {
        JsonNode resp = null;
        try {
            resp = ApiClient.get(DECISION_QUALITY, JsonNode.class);
        } catch (Throwable ignored) {}
        if (resp == null) return new DecisionSnapshot(decisionScore, decisionTrend);
        JsonNode payload = resp;
        if (payload.has("data") && payload.get("data").isObject())
            payload = payload.get("data");
        else if (payload.has("value") && payload.get("value").isObject())
            payload = payload.get("value");
        int s = payload.path("score").asInt(Integer.MIN_VALUE);
        String t = payload.path("trend").asText(null);
        if (s == Integer.MIN_VALUE) s = decisionScore;
        if (t == null) t = decisionTrend;
        return new DecisionSnapshot(clamp0to100(s), t);
    }

    // ---- Engine commands with feedback ----
    private void callEngineCommand(String path) {
        try {
            String raw = ApiClient.post(path, M.createObjectNode(), String.class);
            boolean ok = isEngineOk(raw);
            showNotification(
                    ok ? "Command executed successfully" : "Command failed",
                    ok ? NotificationVariant.LUMO_SUCCESS : NotificationVariant.LUMO_ERROR
            );
            if (ok) {
                String state = extractEngineState(raw);
                if (!state.isEmpty()) {
                    updateEngineStatus(state.toUpperCase(Locale.ROOT));
                }
            }
        } catch (Throwable t) {
            showNotification("Command execution error", NotificationVariant.LUMO_ERROR);
        }
    }

    private void updateEngineStatus(String state) {
        if (state == null) state = "UNKNOWN";
        final String s = state.trim().isEmpty() ? "UNKNOWN" : state.trim().toUpperCase();
        engineStatus.setText(s);
        var tl = engineStatus.getElement().getThemeList();
        tl.remove("success");
        tl.remove("error");
        tl.remove("contrast");

        String color;
        switch (s) {
            case "STARTED": case "RUNNING": case "ON": case "OK":
                tl.add("success");
                color = "#059669";
                break;
            case "STOPPED": case "OFF": case "FAILED": case "ERROR":
                tl.add("error");
                color = "#dc2626";
                break;
            default:
                tl.add("contrast");
                color = "#475569";
        }
        engineStatus.getStyle().set("color", color);
    }

    private boolean isEngineOk(String raw) {
        if (raw == null) return false;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return false;
        if ("ok".equals(s)) return true;
        if (s.contains("engine:started") || s.contains("engine:stopped")) return true;
        try {
            JsonNode n = M.readTree(raw);
            if (n.path("ok").asBoolean(false)) return true;
            String eng = n.path("engine").asText("");
            if ("started".equalsIgnoreCase(eng) || "stopped".equalsIgnoreCase(eng)) return true;
            String status = n.path("status").asText("");
            return "ok".equalsIgnoreCase(status);
        } catch (Exception ignore) {}
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
        switch (trend.toUpperCase(Locale.ROOT)) {
            case "BULLISH":
                trendIcon.setIcon(VaadinIcon.ARROW_UP);
                trendChip.getStyle().set("color", "#059669");
                statusIndicator.getStyle().set("background-color", "#10b981");
                break;
            case "BEARISH":
                trendIcon.setIcon(VaadinIcon.ARROW_DOWN);
                trendChip.getStyle().set("color", "#dc2626");
                statusIndicator.getStyle().set("background-color", "#ef4444");
                break;
            default:
                trendIcon.setIcon(VaadinIcon.MINUS);
                trendChip.getStyle().set("color", "#64748b");
                statusIndicator.getStyle().set("background-color", "#94a3b8");
        }
    }

    private void updateConfidenceScore(int score) {
        int validScore = clamp0to100(score);
        this.decisionScore = validScore;
        confidenceValue.setText(validScore + "%");
        confidenceProgress.getStyle().set("width", validScore + "%");
        String color;
        if (validScore >= 70) {
            color = "#059669";
        } else if (validScore >= 40) {
            color = "#ea580c";
        } else {
            color = "#dc2626";
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
            } catch (Throwable ignore) {}
        }
    }

    // ---- Data container ----
    private static class DecisionSnapshot {
        final int score;
        final String trend;
        public DecisionSnapshot(int score, String trend) {
            this.score = score;
            this.trend = trend;
        }
    }

    private void animateAction(Runnable action) {
        action.run();
    }
}

