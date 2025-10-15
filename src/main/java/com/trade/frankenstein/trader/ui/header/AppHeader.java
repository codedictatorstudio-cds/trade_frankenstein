package com.trade.frankenstein.trader.ui.header;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.ui.bridge.EngineApiClient;
import com.vaadin.flow.component.*;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Premium App Header:
 * - Left: brand
 * - Right: live status chips fed by SSE (StreamGateway) + 5s fallback poller
 * - Fills the entire width; dark gradient background via theme.css
 */
@CssImport("styles/theme.css")
@CssImport("styles/premium-header.css")
public class AppHeader extends Composite<Div> {

    private static final Logger log = Logger.getLogger(AppHeader.class.getName());

    private final EngineApiClient engineApi;
    private final ObjectMapper mapper = new ObjectMapper();

    // Root & zones
    private final HorizontalLayout root = new HorizontalLayout();
    private final HorizontalLayout left = new HorizontalLayout();
    private final HorizontalLayout right = new HorizontalLayout();

    // Brand
    private final Span brand = new Span("Trade");
    private final Span brandAccent = new Span("Frankenstein");

    // Chips
    private final Span engineChip = new Span();
    private final Span sseChip = new Span();
    private final Span modelIdChip = new Span();
    private final Span inferP95Chip = new Span();
    private final Span versionChip = new Span();
    private final Span clock = new Span();

    // State
    private ScheduledThreadPoolExecutor poller;

    public AppHeader(EngineApiClient engineApiClient) {
        this.engineApi = Objects.requireNonNull(engineApiClient, "engineApiClient");

        // Root container
        getContent().setSizeFull();
        root.addClassNames("tf-premium-header");
        root.setWidthFull();
        root.setHeight("64px");
        root.setAlignItems(FlexComponent.Alignment.CENTER);
        root.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        root.setPadding(false);
        root.setSpacing(false);
        root.setMargin(false);

        // Brand (baseline aligned)
        brand.addClassNames("tf-premium-brand");
        brandAccent.addClassNames("tf-premium-brand__accent");
        HorizontalLayout brandWrap = new HorizontalLayout(brand, brandAccent);
        brandWrap.setPadding(false);
        brandWrap.setMargin(false);
        brandWrap.setSpacing(false);
        brandWrap.setAlignItems(FlexComponent.Alignment.BASELINE);

        left.addClassNames("tf-premium-header__left");
        left.setPadding(false);
        left.setMargin(false);
        left.setSpacing(false);
        left.add(brandWrap);

        // Chips (right) — fill remaining space
        Arrays.asList(engineChip, sseChip, modelIdChip, inferP95Chip, versionChip, clock)
                .forEach(ch -> ch.addClassNames("tf-premium-chip"));

        sseChip.getElement().setAttribute("role", "status");
        sseChip.getElement().setAttribute("aria-live", "polite");

        right.addClassNames("tf-premium-header__right");
        right.setWidthFull();
        right.setPadding(false);
        right.setMargin(false);
        right.setSpacing(false);
        right.setAlignItems(FlexComponent.Alignment.CENTER);
        right.getStyle().set("flex", "1 1 auto"); // <- consume free space
        right.add(engineChip, sseChip, modelIdChip, inferP95Chip, versionChip, clock);

        // Layout grow rules (left fixed, right elastic)
        root.setFlexGrow(0, left);
        root.setFlexGrow(1, right);

        // CRITICAL: mount children into root, and root into the host
        root.add(left, right);
        getContent().add(root);

        // Defaults
        applyEngineStatus("UNKNOWN");
        setSseHealthy(false);
        applyModelId("-");
        applyInferP95(-1);
        setBuildMeta("-", "-");
        clock.addClassNames("tf-premium-clock");
    }

    // ===== lifecycle =====
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        UI ui = attachEvent.getUI();
        log.info("AppHeader attached");

        // 1) IST client clock
        ui.getPage().executeJs(
                "const tick=()=>{const d=new Date();" +
                        "const f=new Intl.DateTimeFormat('en-IN',{timeZone:'Asia/Kolkata',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});" +
                        "const s=f.format(d).replace(/\\u200E/g,''); $0.$server.__clock(s);};" +
                        "clearInterval(window.__tfClockTimer); window.__tfClockTimer=setInterval(tick,1000); tick();",
                getElement()
        );

        // 2) SSE bridge — tries common endpoints from StreamGateway
        ui.getPage().executeJs(
                "(()=>{try{" +
                        " const urls=[" +
                        "  '/api/stream?topics=heartbeat,engine.state,flags'," +
                        "  '/stream/sse?topics=heartbeat,engine.state,flags'," +
                        "  '/stream?topics=heartbeat,engine.state,flags'," +
                        "  '/sse?topics=heartbeat,engine.state,flags'];" +
                        " if(window.__tfES){try{window.__tfES.close();}catch(e){}}" +
                        " const root=$0;" +
                        " const send=(type,obj)=>{try{root.$server.onSse(type, JSON.stringify(obj||{}));}catch(e){}};" +
                        " const openNext=(i)=>{" +
                        "   if(i>=urls.length){send('sse.health',{ok:false}); return;}" +
                        "   const es=new EventSource(urls[i]); let opened=false;" +
                        "   es.onopen=()=>{opened=true; window.__tfES=es; send('sse.health',{ok:true});};" +
                        "   es.onerror=()=>{try{es.close();}catch(e){} if(!opened) openNext(i+1); else send('sse.health',{ok:false});};" +
                        "   es.onmessage=(ev)=>{let o=null; try{o=JSON.parse(ev.data||'{}');}catch(e){} if(o){send(o.topic||o.event||'heartbeat',o);} };" +
                        " };" +
                        " openNext(0);" +
                        "}catch(e){ $0.$server.onSse('sse.health','{\"ok\":false}'); }})();",
                getElement()
        );

        ui.getPage().executeJs(
                "fetch('/api/header/snapshot')" +
                        "  .then(r=>r.json())" +
                        "  .then(o=> $0.$server.onSse('heartbeat', JSON.stringify(o)))" +
                        "  .catch(err => console.error('Snapshot fetch failed', err));",
                getElement()
        );

        // 3) Fallback polling (5s)
        if (poller == null) {
            poller = new ScheduledThreadPoolExecutor(1);
            poller.setRemoveOnCancelPolicy(true);
            poller.scheduleWithFixedDelay(() -> {
                try {
                    final String st = engineApi != null ? engineApi.status() : "UNKNOWN";
                    UI cur = UI.getCurrent();
                    if (cur != null && cur.isAttached()) cur.access(() -> applyEngineStatus(st));
                } catch (Throwable ignore) { /* best-effort */ }
            }, 0, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (poller != null) {
            try {
                poller.shutdownNow();
            } catch (Throwable ignored) {
            }
            poller = null;
        }
        getUI().ifPresent(ui -> ui.getPage().executeJs(
                "try{ if(window.__tfES){window.__tfES.close(); window.__tfES=null;} clearInterval(window.__tfClockTimer);}catch(e){}"
        ));
    }

    // ===== SSE bridge → UI =====
    @ClientCallable
    public void onSse(String topic, String json) {
        if (topic == null) return;
        try {
            JsonNode node = mapper.readTree(json == null ? "{}".getBytes(StandardCharsets.UTF_8) : json.getBytes(StandardCharsets.UTF_8));
            switch (topic) {
                case "sse.health":
                    setSseHealthy(node.path("ok").asBoolean(false));
                    break;
                case "heartbeat":
                    applyHeartbeat(node);
                    break;
                case "engine.state":
                case "engine":
                    applyEngineState(node);
                    break;
                default: /* ignore */
            }
        } catch (Exception ignore) { /* best-effort */ }
    }

    // ===== Apply payloads =====
    private void applyHeartbeat(JsonNode hb) {
        if (hb == null || hb.isMissingNode()) return;
        JsonNode st = hb.at("/engine/status");
        if (!st.isMissingNode()) applyEngineStatus(st.asText());
        JsonNode mm = hb.at("/modelMode");
        JsonNode mid = hb.at("/modelId");
        if (!mid.isMissingNode()) applyModelId(mid.asText());
        JsonNode p95 = hb.at("/infer/p95");
        if (p95.isNumber()) applyInferP95(p95.asLong(-1));
        JsonNode ver = hb.at("/build/version");
        JsonNode bts = hb.at("/build/time");
        if (!ver.isMissingNode() || !bts.isMissingNode())
            setBuildMeta(ver.isMissingNode() ? "-" : ver.asText(), bts.isMissingNode() ? "-" : bts.asText());
    }

    private void applyEngineState(JsonNode state) {
        if (state == null || state.isMissingNode()) return;

        final boolean running = state.path("running").asBoolean(false);
        final String lastError = nvl(state.path("lastError").asText(null), "");
        final String startedAt = nvl(state.path("startedAt").asText(null), "");
        final String lastTick = nvl(state.path("lastTick").asText(null), "");
        final long ticks = state.path("ticks").asLong(-1);
        final long lastExecMs = state.path("lastExecuted").asLong(-1);
        final String asOf = nvl(state.path("asOf").asText(null), "");

        // Staleness heuristic: warn if lastTick or asOf is too old
        long ageSec = -1;
        try {
            java.time.Instant ref = !asOf.isEmpty() ? java.time.Instant.parse(asOf)
                    : (!lastTick.isEmpty() ? java.time.Instant.parse(lastTick) : null);
            if (ref != null) ageSec = java.time.Duration.between(ref, java.time.Instant.now()).getSeconds();
        } catch (Throwable ignore) { /* best-effort */ }

        // Decide status + chip variant
        String text;
        String variant;
        if (!lastError.isEmpty()) {
            text = "Engine: ERROR";
            variant = "bad";
        } else if (running) {
            boolean stale = ageSec >= 0 && ageSec > 10; // adjust threshold if needed
            text = "Engine: RUNNING";
            variant = stale ? "warn" : "ok";
        } else {
            text = "Engine: STOPPED";
            variant = "muted";
        }

        // Update the main engine chip
        styleChip(engineChip, text, variant);

        // (Optional) If you want a quick detail tooltip with your fields:
        StringBuilder tip = new StringBuilder();
        tip.append("asOf: ").append(asOf.isEmpty() ? "–" : asOf)
                .append(" • lastTick: ").append(lastTick.isEmpty() ? "–" : lastTick)
                .append(" • ticks: ").append(ticks < 0 ? "–" : String.valueOf(ticks))
                .append(" • lastExec: ").append(lastExecMs < 0 ? "–" : lastExecMs + "ms");
        if (!startedAt.isEmpty()) tip.append(" • started: ").append(startedAt);
        if (!lastError.isEmpty()) tip.append(" • error: ").append(lastError);

        engineChip.getElement().setProperty("title", tip.toString());
    }

    // ===== Chip helpers =====
    private void setSseHealthy(boolean ok) {
        styleChip(sseChip, ok ? "SSE: OK" : "SSE: OFF", ok ? "ok" : "warn");
    }

    public void applyEngineStatus(String status) {
        final String s = status == null ? "UNKNOWN" : status.trim().toUpperCase(Locale.ENGLISH);
        String variant = "muted";
        if ("RUNNING".equals(s)) variant = "ok";
        else if ("STOPPING".equals(s) || "PAUSING".equals(s)) variant = "warn";
        else if ("ERROR".equals(s) || "DOWN".equals(s) || "FAILED".equals(s)) variant = "bad";
        styleChip(engineChip, "Engine: " + s, variant);
    }

    public void setBuildMeta(String version, String buildTime) {
        styleChipOutlined(versionChip, "v" + nvl(version, "-") + " • " + nvl(buildTime, "-"));
    }

    private void applyModelId(String id) {
        styleChip(modelIdChip, "Id: " + nvl(id, "-"), "muted");
    }

    private void applyInferP95(long ms) {
        if (ms <= 0) {
            styleChip(inferP95Chip, "p95: –", "muted");
            return;
        }
        String variant = (ms > 1500) ? "bad" : (ms > 800) ? "warn" : "ok";
        styleChip(inferP95Chip, "p95: " + ms + "ms", variant);
    }

    private static void styleChip(Span chip, String text, String variant) {
        chip.setText(text);
        chip.getClassNames().removeAll(Arrays.asList("ok", "warn", "bad", "muted", "outlined"));
        if (!chip.getClassNames().contains("tf-premium-chip")) chip.addClassNames("tf-premium-chip");
        if (variant != null && !variant.isEmpty()) chip.addClassNames(variant);
    }

    private static void styleChipOutlined(Span chip, String text) {
        chip.setText(text);
        chip.getClassNames().removeAll(Arrays.asList("ok", "warn", "bad", "muted"));
        if (!chip.getClassNames().contains("tf-premium-chip")) chip.addClassNames("tf-premium-chip");
        chip.addClassNames("outlined");
    }

    private static String nvl(String s, String d) {
        return (s == null || s.trim().isEmpty()) ? d : s;
    }

    // Client clock callback
    @ClientCallable
    private void __clock(String timeString) {
        if (timeString != null) clock.setText(timeString);
    }
}