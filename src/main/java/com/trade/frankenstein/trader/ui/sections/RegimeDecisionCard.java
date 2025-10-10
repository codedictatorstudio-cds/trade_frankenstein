package com.trade.frankenstein.trader.ui.sections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.ui.bridge.ApiClient;
import com.vaadin.flow.component.*;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.shared.Registration;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class RegimeDecisionCard extends Div {

    // Header: title + (trend chip + mini progress)
    private final Span title = new Span("Regime & Decision");
    private final Span trendChip = new Span("Trend");
    private final Div progressTrack = new Div(); // fixed width container (140px)
    private final Div progressFill = new Div();  // fills by width %

    // Ring with center number
    private final Div ring = new Div();
    private final Span ringVal = new Span("72");
    private final Span ringLabel = new Span("Confidence");

    // Tags (RR, Slip, Throttle)
    private final Span rrTag = pill("RR —");
    private final Span slipTag = pill("Slip —");
    private final Span thrTag = pill("Throttle —");

    // Reasons
    private final UnorderedList reasons = new UnorderedList();
    private final Span reasonsHeader = new Span("Key Indicators");

    // Colors (no yellow/orange)
    private static final String COLOR_PRIMARY = "#1a56db";
    private static final String COLOR_PRIMARY_LIGHT = "#e6f0ff";
    private static final String COLOR_PRIMARY_DARK = "#0c4a6e";
    private static final String COLOR_SUCCESS = "#059669";
    private static final String COLOR_SUCCESS_LIGHT = "#d1fae5";
    private static final String COLOR_LOW = "#6366f1";
    private static final String COLOR_TEXT = "#1f2937";
    private static final String COLOR_TEXT_SECONDARY = "#6b7280";
    private static final String COLOR_BACKGROUND = "#ffffff";
    private static final String COLOR_BORDER = "#e5e7eb";
    private static final String COLOR_PROGRESS_BG = "#f9fafb";
    private static final String COLOR_DANGER = "#dc2626";

    private int confidence = 72;
    private Registration busReg;

    // JSON parser (replace ApiClient.readTree)
    private static final ObjectMapper OM = new ObjectMapper();

    // New: chips from backend "tags" map
    private final HorizontalLayout tagChips = new HorizontalLayout();

    // New: minimal skeleton state
    private final Div skeleton = new Div();
    private volatile boolean hasData = false;

    // Centralize thresholds
    private static final int THRESH_EXCELLENT = 80;
    private static final int THRESH_GOOD = 60;
    private static final int THRESH_OK = 40;

    // Optionally remember the last SSE heartbeat
    private volatile long lastEventAt = 0L;

    // Endpoint as a constant
    private static final String PATH_DECISION_QUALITY = "/api/decision/quality";

    // Fallback polling
    private Timer refreshTimer;
    private final AtomicBoolean attached = new AtomicBoolean(false);

    public RegimeDecisionCard() {
        addClassName("premium-regime-card");

        // Container
        getStyle()
                .set("background", COLOR_BACKGROUND)
                .set("border", "none")
                .set("border-radius", "16px")
                .set("padding", "20px")
                .set("box-shadow", "0 10px 25px -5px rgba(0, 0, 0, 0.05), 0 8px 10px -6px rgba(0, 0, 0, 0.01)")
                .set("transition", "all 0.2s ease-in-out")
                .set("overflow", "hidden")  // Prevent overflow issues
                .set("min-width", "400px"); // Ensure minimum width

        // Header left (title + tags)
        title.getStyle()
                .set("font-size", "16px")
                .set("font-weight", "800")
                .set("letter-spacing", "-0.3px")
                .set("margin-bottom", "0")
                .set("color", COLOR_TEXT)
                .set("font-family", "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif")
                .set("white-space", "nowrap"); // Prevent title wrapping

        // Improved tag layout with flex-wrap
        HorizontalLayout tags = new HorizontalLayout();
        tags.setPadding(false);
        tags.setSpacing(false);
        tags.getStyle()
                .set("flex-wrap", "wrap")
                .set("gap", "8px")
                .set("width", "100%");

        tags.add(rrTag, slipTag, thrTag);

        VerticalLayout hdLeft = new VerticalLayout();
        hdLeft.setSpacing(false);
        hdLeft.setPadding(false);
        hdLeft.setWidth(null); // Allow natural width
        hdLeft.getStyle()
                .set("gap", "10px")
                .set("max-width", "250px"); // Limit width to prevent pushing other elements
        hdLeft.add(title, tags);

        // Trend chip
        trendChip.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("gap", "8px")
                .set("padding", "6px 12px")
                .set("border-radius", "999px")
                .set("background", COLOR_PRIMARY_LIGHT)
                .set("color", COLOR_PRIMARY)
                .set("font-weight", "600")
                .set("font-size", "13px")
                .set("box-shadow", "0 1px 2px rgba(0, 0, 0, 0.05)")
                .set("transition", "transform 0.2s")
                .set("white-space", "nowrap");

        Div dot = new Div();
        dot.getStyle()
                .set("width", "8px")
                .set("height", "8px")
                .set("flex-shrink", "0") // Prevent shrinking
                .set("border-radius", "999px")
                .set("background", COLOR_PRIMARY);

        trendChip.addComponentAsFirst(dot);

        // Progress
        progressTrack.getStyle()
                .set("width", "120px") // Slightly reduced width
                .set("min-width", "80px") // Ensure minimum width
                .set("flex-shrink", "1") // Allow some shrinking if needed
                .set("height", "8px")
                .set("border-radius", "999px")
                .set("background", COLOR_PROGRESS_BG)
                .set("overflow", "hidden")
                .set("box-shadow", "inset 0 1px 2px rgba(0, 0, 0, 0.05)");

        progressFill.getStyle()
                .set("height", "100%")
                .set("background", "linear-gradient(90deg, " + COLOR_PRIMARY + ", " + COLOR_SUCCESS + ")")
                .set("width", confidence + "%")
                .set("transition", "width 0.5s ease-in-out");

        // Improved header right layout
        HorizontalLayout hdRight = new HorizontalLayout();
        hdRight.setSpacing(false);
        hdRight.setPadding(false);
        hdRight.setAlignItems(FlexComponent.Alignment.CENTER);
        hdRight.getStyle()
                .set("gap", "12px") // Reduced gap
                .set("flex-shrink", "0"); // Prevent shrinking
        hdRight.add(trendChip, progressTrack);

        // Improved header layout with proper spacing and alignment
        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setPadding(false);
        header.setSpacing(false);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.add(hdLeft, hdRight);
        header.getStyle().set("flex-wrap", "wrap-reverse"); // Allow wrapping if needed

        // Add progressFill to progressTrack
        progressTrack.add(progressFill);

        // Confidence "ring" (premium square)
        ring.getStyle()
                .set("width", "160px")
                .set("height", "160px")
                .set("border-radius", "16px")
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("align-items", "center")
                .set("justify-content", "center")
                .set("position", "relative")
                .set("margin", "10px auto") // Center horizontally
                .set("background", "linear-gradient(135deg, " + COLOR_PRIMARY_LIGHT + ", " + COLOR_SUCCESS_LIGHT + ")")
                .set("box-shadow", "0 10px 15px -3px rgba(0, 0, 0, 0.08), 0 4px 6px -4px rgba(0, 0, 0, 0.05)");

        Div arcTrack = new Div();
        arcTrack.getStyle()
                .set("position", "absolute")
                .set("width", "120px")
                .set("height", "60px")
                .set("border-radius", "120px 120px 0 0")
                .set("background", COLOR_BORDER)
                .set("top", "20px")
                .set("overflow", "hidden");

        Div arcFill = new Div();
        arcFill.getStyle()
                .set("position", "absolute")
                .set("width", "120px")
                .set("height", "60px")
                .set("border-radius", "120px 120px 0 0")
                .set("background", "linear-gradient(135deg, " + COLOR_PRIMARY + ", " + COLOR_SUCCESS + ")")
                .set("top", "0")
                .set("clip-path", "polygon(50% 50%, " + (50 - confidence / 2) + "% 0%, " + (50 + confidence / 2) + "% 0%)");

        arcTrack.add(arcFill);

        ringVal.getStyle()
                .set("font-weight", "900")
                .set("font-size", "40px")
                .set("color", COLOR_TEXT)
                .set("margin-top", "40px")
                .set("position", "relative")
                .set("text-align", "center");

        ringLabel.getStyle()
                .set("font-size", "14px")
                .set("font-weight", "600")
                .set("color", COLOR_TEXT_SECONDARY)
                .set("text-transform", "uppercase")
                .set("letter-spacing", "0.5px")
                .set("text-align", "center");

        Span confidenceLevel = new Span("Good");
        confidenceLevel.getStyle()
                .set("font-size", "12px")
                .set("font-weight", "700")
                .set("padding", "4px 12px")
                .set("border-radius", "999px")
                .set("margin-top", "8px")
                .set("color", "#fff")
                .set("background", COLOR_SUCCESS);

        ring.add(arcTrack, ringVal, ringLabel, confidenceLevel);

        // Reasons header + list with improved layout
        reasonsHeader.getStyle()
                .set("font-size", "14px")
                .set("font-weight", "700")
                .set("color", COLOR_TEXT)
                .set("margin-top", "4px")
                .set("margin-bottom", "8px")
                .set("display", "block"); // Ensure block display

        reasons.getStyle()
                .set("margin", "0")
                .set("padding-left", "24px")
                .set("width", "100%")
                .set("box-sizing", "border-box"); // Include padding in width calculation

        Div reasonsContainer = new Div();
        reasonsContainer.getStyle()
                .set("width", "100%")
                .set("padding", "14px 18px")
                .set("background", COLOR_PRIMARY_LIGHT + "33")
                .set("border-radius", "12px")
                .set("margin-top", "16px")
                .set("box-sizing", "border-box") // Include padding in width calculation
                .set("box-shadow", "inset 0 2px 4px rgba(0, 0, 0, 0.02)");
        reasonsContainer.add(reasonsHeader, reasons);

        // Body with improved layout
        VerticalLayout body = new VerticalLayout();
        body.setPadding(false);
        body.setSpacing(false);
        body.setWidthFull();
        body.setAlignItems(FlexComponent.Alignment.CENTER);
        body.getStyle()
                .set("gap", "16px")
                .set("margin-top", "10px"); // Add some space below header
        body.add(ring, reasonsContainer);

        add(header, body);

        // Cross-app confidence updates (keep existing behavior)
        UI.getCurrent().getElement().addEventListener("confidence-changed", e -> {
            try {
                confidence = Integer.parseInt(e.getEventData().getString("event.detail"));
                confidence = Math.max(0, Math.min(100, confidence));
                updateVisuals();
            } catch (Exception ignored) {
            }
        }).addEventData("event.detail");

        busReg = ComponentUtil.addListener(UI.getCurrent(), ConfidenceChangedEvent.class, ev -> {
            confidence = Math.max(0, Math.min(100, ev.getConfidence()));
            updateVisuals();
        });

        // Also listen for full decision-quality JSON (for all fields)
        UI.getCurrent().getElement().addEventListener("decision-quality", e -> {
            String json = e.getEventData().getString("event.detail");
            try {
                JsonNode j = OM.readTree(json);
                applyDecisionQuality(j, true);
            } catch (Throwable ignored) {
            }
        }).addEventData("event.detail");

        // Chips bar (follows RR/Slip/Throttle)
        tagChips.setPadding(false);
        tagChips.setSpacing(false);
        tagChips.getStyle()
                .set("flex-wrap", "wrap")
                .set("gap", "8px")
                .set("width", "100%");

        // Skeleton shimmer
        skeleton.getStyle()
                .set("width", "100%")
                .set("height", "96px")
                .set("border-radius", "12px")
                .set("background", "linear-gradient(90deg, #f3f4f6 25%, #e5e7eb 37%, #f3f4f6 63%)")
                .set("background-size", "400% 100%")
                .set("animation", "shimmer 1.4s ease infinite")
                .set("margin-top", "8px");

        // Inject a keyframes rule once
        getElement().executeJs(
                "if(!document.getElementById('rdc-kf')){" +
                        " const s=document.createElement('style'); s.id='rdc-kf';" +
                        " s.textContent='@keyframes shimmer{0%{background-position:100% 0}100%{background-position:-100% 0}}';" +
                        " document.head.appendChild(s);" +
                        "}"
        );

        // Add chips & skeleton under the existing tags block
        // (Find where you do: hdLeft.add(title, tags); then append the following line)
        hdLeft.add(tagChips, skeleton);

        // Sync initial visuals
        updateVisuals();
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        attached.set(true);

        // Initial fetch (REST): /api/decision/quality
        fetchAndApplyDecisionQuality();

        // SSE: forward decision.quality → pass full JSON & also fire confidence-changed
        getUI().ifPresent(ui -> ui.getPage().executeJs("""
                  const target = $0;
                  function parse(x){ try { return JSON.parse(x||'{}'); } catch(_) { return {}; } }
                  window.addEventListener('decision.quality', (e) => {
                    const j = parse(e.detail);
                    const s = (j.score ?? j.confidence ?? 0)|0;
                    // Full payload for server-side handling:
                    target.dispatchEvent(new CustomEvent('decision-quality', { detail: JSON.stringify(j) }));
                    // Back-compat for any client-side listeners:
                    target.dispatchEvent(new CustomEvent('confidence-changed', { detail: String(s) }));
                  });
                """, ui.getElement()));

        // Secondary fallback: periodic refresh every 30s (cleared on detach)
        startRefreshTimer();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        attached.set(false);
        if (busReg != null) busReg.remove();
        stopRefreshTimer();
    }

    public static class ConfidenceChangedEvent extends ComponentEvent<Component> {
        private final int confidence;

        public ConfidenceChangedEvent(Component source, boolean fromClient, int confidence) {
            super(source, fromClient);
            this.confidence = confidence;
        }

        public int getConfidence() {
            return confidence;
        }
    }

    /* --------------------------- Data wiring --------------------------- */

    private void fetchAndApplyDecisionQuality() {
        try {
            // If we got an SSE in the last 5s, skip a redundant fetch
            if (System.currentTimeMillis() - lastEventAt < 5000) return;

            JsonNode j = ApiClient.get(PATH_DECISION_QUALITY, JsonNode.class);
            if (j != null) {
                applyDecisionQuality(j, true);
                lastEventAt = System.currentTimeMillis();
            }
        } catch (Throwable ignored) {
            // Keep skeleton visible only if no data yet; otherwise stay quiet
            if (!hasData) skeleton.setVisible(true);
        }
    }

    private void applyDecisionQuality(JsonNode j, boolean fireConfidenceChanged) {
        if (j == null) return;

        hasData = true;
        skeleton.setVisible(false);

        // Score / confidence
        int s = j.path("score").asInt(j.path("confidence").asInt(confidence));
        confidence = Math.max(0, Math.min(100, s));

        // Trend: "Bullish" | "Bearish" | "Neutral"
        String trend = j.path("trend").asText("");
        setTrendChip(trend);

        // RR
        String rrTxt = j.hasNonNull("rr") ? "RR: " + j.get("rr").asText()
                : j.hasNonNull("riskReward") ? "RR: " + j.get("riskReward").asText()
                : "RR —";
        setPill(rrTag, rrTxt, COLOR_PRIMARY, COLOR_PRIMARY_LIGHT);

        // Slippage
        String slipTxt = "Slip —";
        if (j.hasNonNull("slippagePct")) slipTxt = "Slip est: " + j.get("slippagePct").asText() + "%";
        else if (j.hasNonNull("slippage")) slipTxt = "Slip est: " + j.get("slippage").asText();
        setPill(slipTag, slipTxt, COLOR_PRIMARY, COLOR_PRIMARY_LIGHT);

        // Throttle
        String thr = j.path("throttle").asText(j.path("throttleState").asText(""));
        if ("HALT".equalsIgnoreCase(thr) || "TRIPPED".equalsIgnoreCase(thr)) {
            setPill(thrTag, "Throttle: HALT", "#fff", COLOR_DANGER);
        } else if ("OK".equalsIgnoreCase(thr) || thr.isEmpty()) {
            setPill(thrTag, "Throttle: OK", COLOR_SUCCESS, COLOR_SUCCESS_LIGHT);
        } else {
            setPill(thrTag, "Throttle: " + thr.toUpperCase(), COLOR_PRIMARY, COLOR_PRIMARY_LIGHT);
        }

        // Reasons
        reasons.removeAll();
        if (j.has("reasons") && j.get("reasons").isArray() && j.get("reasons").size() > 0) {
            int count = 0, total = j.get("reasons").size();
            for (JsonNode r : j.get("reasons")) {
                if (count < 6) {
                    reasons.add(styledListItem(r.asText()));
                }
                count++;
            }
            if (total > 6) {
                reasons.add(styledListItem("+" + (total - 6) + " more"));
            }
        } else {
            reasons.add(styledListItem("Signals stable"));
        }
        renderTagChips(j);
        // Visuals
        updateVisuals();

        // Broadcast "confidence-changed" for other components
        if (fireConfidenceChanged && getUI().isPresent()) {
            int sFinal = confidence;
            getUI().get().getPage().executeJs("""
                        const target = $0;
                        target.dispatchEvent(new CustomEvent('confidence-changed', { detail: String($1) }));
                    """, getUI().get().getElement(), sFinal);
        }
    }

    private void setTrendChip(String trendRaw) {
        String t = (trendRaw == null ? "" : trendRaw.trim());
        String display = t.isEmpty() ? "Neutral" : t.substring(0, 1).toUpperCase() + t.substring(1).toLowerCase();
        trendChip.setText(display);

        // Color by trend
        String bg = COLOR_PRIMARY_LIGHT, fg;
        if ("bullish".equalsIgnoreCase(t)) {
            bg = COLOR_SUCCESS_LIGHT;
            fg = COLOR_SUCCESS;
        } else if ("bearish".equalsIgnoreCase(t)) {
            bg = "#fee2e2";
            fg = COLOR_DANGER;
        } else {
            fg = COLOR_PRIMARY;
        }

        // First child is the dot
        trendChip.getChildren().findFirst().ifPresent(c -> c.getElement().getStyle().set("background", fg));
        trendChip.getStyle().set("background", bg).set("color", fg);
    }

    private static void setPill(Span pill, String txt, String fg, String bg) {
        pill.setText(txt);
        pill.getStyle().set("color", fg).set("background", bg);
    }

    /* --------------------------- Visuals --------------------------- */

    private void updateVisuals() {
        // Level text + color
        String levelText;
        String levelColor;
        if (confidence > 80) {
            levelText = "Excellent";
            levelColor = COLOR_SUCCESS;
        } else if (confidence > 60) {
            levelText = "Good";
            levelColor = COLOR_PRIMARY;
        } else if (confidence > 40) {
            levelText = "Fair";
            levelColor = COLOR_PRIMARY_DARK;
        } else {
            levelText = "Low";
            levelColor = COLOR_LOW;
        }

        // 4th child of ring (confidenceLevel)
        if (ring.getChildren().count() >= 4) {
            Component comp = ring.getChildren().skip(3).findFirst().orElse(null);
            if (comp instanceof Span) {
                ((Span) comp).setText(levelText);
                comp.getStyle().set("background", levelColor);
            }
        }

        // Update arc fill
        ring.getChildren().findFirst().ifPresent(arcTrackComp -> {
            if (arcTrackComp instanceof Div) {
                Div arcTrack = (Div) arcTrackComp;
                arcTrack.getChildren().findFirst().ifPresent(arcFillComp -> {
                    if (arcFillComp instanceof Div) {
                        ((Div) arcFillComp).getStyle()
                                .set("clip-path", "polygon(50% 50%, " + (50 - confidence / 2) + "% 0%, " + (50 + confidence / 2) + "% 0%)")
                                .set("background", "linear-gradient(135deg, " + COLOR_PRIMARY + ", " +
                                        (confidence > 60 ? COLOR_SUCCESS : COLOR_LOW) + ")");
                    }
                });
            }
        });

        String[] cc = pickConfidenceColors(confidence);
        String fg = cc[0], bg = cc[1];

        ringVal.setText(String.valueOf(confidence));
        progressFill.getStyle().set("width", confidence + "%");

        // Progress color nuance
        if (confidence > 80) {
            progressFill.getStyle().set("background",
                    (confidence > THRESH_EXCELLENT) ? "linear-gradient(90deg, " + COLOR_PRIMARY + ", " + COLOR_SUCCESS + ")" : fg);
        } else if (confidence > 40) {
            progressFill.getStyle().set("background", COLOR_PRIMARY);
        } else {
            progressFill.getStyle().set("background", "linear-gradient(90deg, " + COLOR_LOW + ", " + COLOR_PRIMARY + ")");
        }
    }

    private static Span pill(String txt) {
        Span s = new Span(txt);
        s.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("gap", "6px")
                .set("padding", "6px 10px") // Slightly reduced padding
                .set("border-radius", "999px")
                .set("background", COLOR_PRIMARY_LIGHT)
                .set("color", COLOR_PRIMARY)
                .set("font-weight", "600")
                .set("font-size", "12px")
                .set("max-width", "100%") // Ensure it doesn't exceed container
                .set("overflow", "hidden") // Hide text overflow
                .set("text-overflow", "ellipsis") // Show ellipsis for overflowing text
                .set("white-space", "nowrap") // Keep on single line
                .set("box-shadow", "0 1px 2px rgba(0, 0, 0, 0.05)")
                .set("transition", "all 0.2s ease");
        s.getElement().addEventListener("mouseover", e ->
                s.getStyle().set("transform", "translateY(-1px)")
                        .set("box-shadow", "0 4px 6px -1px rgba(0, 0, 0, 0.1)"));
        s.getElement().addEventListener("mouseout", e ->
                s.getStyle().set("transform", "translateY(0)")
                        .set("box-shadow", "0 1px 2px rgba(0, 0, 0, 0.05)"));
        return s;
    }

    private static ListItem styledListItem(String text) {
        ListItem item = new ListItem(text);
        item.getStyle()
                .set("color", COLOR_TEXT)
                .set("font-size", "13px")
                .set("margin-bottom", "6px")
                .set("padding", "2px 0")
                .set("word-break", "break-word") // Allow breaking long words
                .set("overflow-wrap", "break-word") // Ensure text wraps properly
                .set("line-height", "1.4"); // Better line spacing for readability
        return item;
    }

    /* --------------------------- Timer utils --------------------------- */

    private void startRefreshTimer() {
        stopRefreshTimer();
        refreshTimer = new Timer("RegimeDecisionCard-Refresh", true);
        refreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!attached.get()) return;
                getUI().ifPresent(ui -> ui.access(() -> fetchAndApplyDecisionQuality()));
            }
        }, 10000, 10000);
    }

    private void stopRefreshTimer() {
        if (refreshTimer != null) {
            try {
                refreshTimer.cancel();
            } catch (Throwable ignored) {
            }
            refreshTimer = null;
        }
    }

    private void renderTagChips(JsonNode j) {
        tagChips.removeAll();
        if (j == null || !j.has("tags") || !j.get("tags").isObject()) return;

        JsonNode t = j.get("tags");
        // Safe known keys – add more if you emit them
        addTagIfPresent(t, "Confidence");
        addTagIfPresent(t, "Headroom");
        addTagIfPresent(t, "Cooldown");
        addTagIfPresent(t, "DailyLoss");
        addTagIfPresent(t, "Entry");
        addTagIfPresent(t, "Risk");
    }

    private void addTagIfPresent(JsonNode tags, String key) {
        if (!tags.has(key)) return;
        String val = tags.get(key).asText();
        tagChips.add(buildTagChip(key, val));
    }

    private Component buildTagChip(String key, String val) {
        final Span chip = new Span(key + ": " + val);
        chip.getElement().setAttribute("title", key + " = " + val);
        chip.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("padding", "4px 10px")
                .set("border-radius", "999px")
                .set("font-weight", "600")
                .set("font-size", "12px")
                .set("box-shadow", "0 1px 2px rgba(0,0,0,.05)");

        // Color heuristic by key/value
        String fg = COLOR_PRIMARY, bg = COLOR_PRIMARY_LIGHT;
        String v = val.toLowerCase();

        if ("confidence".equalsIgnoreCase(key)) {
            int c = safeInt(val, confidence);
            String[] cc = pickConfidenceColors(c);
            fg = cc[0];
            bg = cc[1];
        } else if ("headroom".equalsIgnoreCase(key)) {
            if (v.contains("high") || v.contains("ok") || v.contains("green")) {
                fg = COLOR_SUCCESS;
                bg = COLOR_SUCCESS_LIGHT;
            } else if (v.contains("low") || v.contains("red")) {
                fg = COLOR_DANGER;
                bg = "#fee2e2";
            }
        } else if ("cooldown".equalsIgnoreCase(key)) {
            if (v.contains("active") || v.contains("yes")) {
                fg = COLOR_DANGER;
                bg = "#fee2e2";
            }
        } else if ("dailyloss".equalsIgnoreCase(key)) {
            if (v.contains("breach") || v.contains("hit")) {
                fg = COLOR_DANGER;
                bg = "#fee2e2";
            } else if (v.contains("%")) {
                try {
                    double pct = Double.parseDouble(v.replace("%", "").trim());
                    if (pct >= 2.0) {
                        fg = COLOR_DANGER;
                        bg = "#fee2e2";
                    } else if (pct >= 1.0) {
                        fg = COLOR_PRIMARY_DARK;
                        bg = COLOR_PRIMARY_LIGHT;
                    } else {
                        fg = COLOR_SUCCESS;
                        bg = COLOR_SUCCESS_LIGHT;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        chip.getStyle().set("color", fg).set("background", bg);
        return chip;
    }

    private static int safeInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private String[] pickConfidenceColors(int c) {
        if (c > THRESH_EXCELLENT) return new String[]{COLOR_SUCCESS, COLOR_SUCCESS_LIGHT};
        if (c > THRESH_GOOD) return new String[]{COLOR_PRIMARY, COLOR_PRIMARY_LIGHT};
        if (c > THRESH_OK) return new String[]{COLOR_PRIMARY_DARK, COLOR_PRIMARY_LIGHT};
        return new String[]{COLOR_LOW, "#fee2e2"};
    }
}
