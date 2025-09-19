package com.trade.frankenstein.trader.ui.header;

import com.trade.frankenstein.trader.service.UpstoxTradeMode;
import com.vaadin.flow.component.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.dom.ThemeList;
import com.vaadin.flow.theme.lumo.LumoUtility;

import static com.trade.frankenstein.trader.ui.bridge.ApiClient.post;

/**
 * ControlsBar — Engine status moved here; Upstox TTL removed.
 * Start/Stop/Kill controls only. Premium look; no orange.
 */
@CssImport("./styles/header-controls.css")
public class ControlsBar extends HorizontalLayout {

    // Palette (blue/green, no orange)
    private static final String BRAND = "#00C853";
    private static final String BRAND_600 = "#00B84E";
    private static final String BRAND_200 = "#D8FAE6";
    private static final String BORDER = "#E6E8F0";
    private static final String DANGER = "#EF4444";
    private static final String INFO = "#2563EB";
    private static final String MUTED = "#6B7280";
    private static final String SURFACE = "#FFFFFF";

    // Refs
    private final RadioButtonGroup<String> modeGroup = new RadioButtonGroup<>();
    private Button startBtn;
    private Button stopBtn;
    private Button killBtn;
    private Div confFill;
    private Span confLabel;
    private Span feedsStatus;
    private Span ordersStatus;

    // Engine status badge now in ControlsBar
    private Span engineStatus;

    public ControlsBar() {
        addClassName("controls-bar");
        getElement().setAttribute("role", "toolbar");

        getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "minmax(280px,1fr) minmax(260px,0.9fr) minmax(360px,1.15fr) minmax(420px,1.25fr)")
                .set("grid-auto-rows", "56px")
                .set("align-items", "stretch")
                .set("gap", "16px")
                .set("padding", "16px 24px")
                .set("box-sizing", "border-box");

        HorizontalLayout actions = createActionButtons();
        HorizontalLayout mode = createModeToggle();
        HorizontalLayout confidence = createConfidenceMeter();
        HorizontalLayout statuses = createStatusBadges(); // includes Engine:, no TTL

        add(actions, mode, confidence, statuses);

        setDefaultVerticalComponentAlignment(Alignment.STRETCH);
        setPadding(false);
        setSpacing(false);

        initializeComponentStates();

        // SSE: decision.quality → confidence
        getUI().ifPresent(ui -> ui.getPage().executeJs(
                "const cmp=$0;function parse(x){try{return JSON.parse(x||'{}')}catch(_){return {}}}" +
                        "addEventListener('decision.quality',e=>{const j=parse(e.detail);" +
                        "const s=Number(j.score??j.confidence??j.value??j.quality??0);cmp.$server._onConfidence(isNaN(s)?0:s);});",
                getElement()
        ));

        // Start
        startBtn.addClickListener(e -> {
            try {
                showRippleEffect(startBtn);
                updateEngineBadge("STARTING"); // optimistic
                post("/api/engine/start", null, Void.class);
            } catch (Throwable ignore) {
            }
        });

        // Stop
        stopBtn.addClickListener(e -> {
            try {
                showRippleEffect(stopBtn);
                updateEngineBadge("STOPPING"); // optimistic
                post("/api/engine/stop", null, Void.class);
            } catch (Throwable ignore) {
            }
        });

        // Kill Switch (UI-only reflect; wire a backend call if you expose one)
        killBtn.addClickListener(e -> confirmKill());
    }

    private HorizontalLayout createActionButtons() {
        Icon playIcon = VaadinIcon.PLAY.create();
        playIcon.setSize("16px");
        startBtn = new Button("Start", playIcon);
        startBtn.addClassName("action-button");
        startBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        styleBtn(startBtn, BRAND, "#fff");

        Icon pauseIcon = VaadinIcon.PAUSE.create();
        pauseIcon.setSize("16px");
        stopBtn = new Button("Stop", pauseIcon);
        stopBtn.addClassName("action-button");
        stopBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        styleBtn(stopBtn, INFO, "#fff");

        Icon stopIcon = VaadinIcon.CLOSE_CIRCLE.create();
        stopIcon.setSize("16px");
        killBtn = new Button("Kill Switch", stopIcon);
        killBtn.addClassName("action-button");
        killBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        styleBtn(killBtn, DANGER, "#fff");

        HorizontalLayout g = compactGroup(startBtn, stopBtn, killBtn);
        g.addClassName("section");
        forceSectionSizing(g);
        return g;
    }

    private void confirmKill() {
        Dialog d = new Dialog();
        d.setCloseOnEsc(true);
        d.setCloseOnOutsideClick(true);
        d.addClassName(LumoUtility.Padding.MEDIUM);

        Icon warningIcon = VaadinIcon.WARNING.create();
        warningIcon.setColor(DANGER);
        warningIcon.setSize("36px");

        Span msg = new Span("Are you sure you want to activate the Kill Switch?");

        Button yes = new Button("Yes, Activate Kill Switch", VaadinIcon.CLOSE_CIRCLE.create());
        styleBtn(yes, DANGER, "#fff");
        yes.addClickListener(e -> {
            showRippleEffect(yes);
            updateEngineBadge("KILLED");
            // Optionally: post("/api/engine/stop", null, Void.class);
            d.close();
        });

        Button cancel = new Button("Cancel", VaadinIcon.ARROW_BACKWARD.create());
        styleBtn(cancel, MUTED, "#fff");
        cancel.addClickListener(e -> {
            showRippleEffect(cancel);
            d.close();
        });

        HorizontalLayout btns = new HorizontalLayout(cancel, yes);
        btns.getStyle().set("gap", "16px");
        Div content = new Div(warningIcon, msg, btns);
        content.getStyle()
                .set("display", "flex")
                .set("flexDirection", "column")
                .set("alignItems", "center")
                .set("gap", "16px")
                .set("padding", "24px");
        d.add(content);
        d.open();
    }

    private HorizontalLayout createModeToggle() {
        modeGroup.setItems("Live", "Sandbox");
        modeGroup.setValue("Sandbox"); // default SANDBOX
        modeGroup.addClassName("mode-selector");
        modeGroup.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("gap", "10px")
                .set("padding", "4px 8px")
                .set("margin", "0")
                .set("background", SURFACE)
                .set("border", "1px solid " + BORDER)
                .set("border-radius", "10px")
                .set("color", MUTED)
                .set("font-weight", "600");

        ThemeList tl = modeGroup.getThemeNames();
        tl.add("mode-toggle");

        modeGroup.addValueChangeListener(e -> {
            boolean sandbox = "Sandbox".equals(e.getValue());
            killBtn.setVisible(!sandbox);
            modeGroup.getStyle().set("border-color", sandbox ? INFO : DANGER);
            UI ui = UI.getCurrent();
            if (ui != null) ComponentUtil.fireEvent(ui, new ModeChangedEvent(this, false, sandbox));
            UpstoxTradeMode mode = UpstoxTradeMode.getInstance();
            if (killBtn.isVisible()){
                mode.updateTradeMode("live");
            } else {
                mode.updateTradeMode("sandbox");
            }
        });

        Div wrap = new Div(modeGroup);
        wrap.getStyle().set("display", "flex").set("alignItems", "center").set("height", "100%");

        Icon icon = VaadinIcon.COG.create();
        icon.setSize("18px");
        icon.addClassName("section-icon");

        HorizontalLayout layout = new HorizontalLayout(icon, wrap);
        layout.setAlignItems(Alignment.CENTER);
        layout.setSpacing(false);
        layout.setPadding(false);

        HorizontalLayout g = compactGroup(layout);
        g.addClassName("section");
        forceSectionSizing(g);
        return g;
    }

    private HorizontalLayout createConfidenceMeter() {
        Div meter = new Div();
        meter.addClassName("confidence-meter");
        meter.getStyle()
                .set("position", "relative")
                .set("flex", "1")
                .set("height", "10px")
                .set("background", "#E5E7EB")
                .set("border-radius", "999px")
                .set("overflow", "hidden");

        confFill = new Div();
        confFill.addClassName("confidence-fill");
        confFill.getStyle()
                .set("height", "100%")
                .set("width", "0%")
                .set("transition", "width 240ms ease");
        meter.add(confFill);

        confLabel = new Span("--%");
        confLabel.addClassName("confidence-label");
        confLabel.getStyle()
                .set("font-weight", "800")
                .set("font-size", "0.95rem")
                .set("line-height", "1")
                .set("padding", "6px 10px")
                .set("border-radius", "999px");

        Icon icon = VaadinIcon.CHART.create();
        icon.setSize("18px");
        icon.addClassName("section-icon");

        Div field = field("Confidence", new HorizontalLayout(meter, confLabel));
        HorizontalLayout inner = (HorizontalLayout) field.getComponentAt(1);
        inner.setPadding(false);
        inner.setSpacing(false);
        inner.setAlignItems(Alignment.CENTER);
        inner.getStyle().set("gap", "12px").set("height", "100%");

        HorizontalLayout content = new HorizontalLayout(icon, field);
        content.setPadding(false);
        content.setSpacing(false);
        content.setAlignItems(Alignment.CENTER);

        HorizontalLayout g = growGroup(content);
        g.addClassName("section");
        forceSectionSizing(g);
        return g;
    }

    private HorizontalLayout createStatusBadges() {
        engineStatus = badge("Engine: NOT STARTED", "err");
        feedsStatus = badge("Feeds: —", "warn");

        Icon icon = VaadinIcon.TASKS.create();
        icon.setSize("18px");
        icon.addClassName("section-icon");

        HorizontalLayout pills = new HorizontalLayout(engineStatus, feedsStatus);
        pills.setPadding(false);
        pills.setSpacing(false);
        pills.setAlignItems(Alignment.CENTER);
        pills.getStyle().set("gap", "6px").set("height", "100%");

        HorizontalLayout content = new HorizontalLayout(icon, pills);
        content.setPadding(false);
        content.setSpacing(false);
        content.setAlignItems(Alignment.CENTER);

        HorizontalLayout g = compactGroup(content);
        g.addClassName("section");
        forceSectionSizing(g);
        return g;
    }

    private void initializeComponentStates() {
        addAttachListener(e -> {
            boolean sandbox = "Sandbox".equals(modeGroup.getValue());
            killBtn.setVisible(!sandbox);
            modeGroup.getStyle().set("border-color", sandbox ? INFO : DANGER);

            // Poll engine state every 5s and reflect in the ControlsBar (accepts JSON or plain text)
            getUI().ifPresent(ui -> ui.getPage().executeJs("""
                        const cmp = $0;
                        async function fetchEngine(){
                          try{
                            const r = await fetch('/api/engine/state', {credentials:'include'});
                            if(!r.ok){ cmp.$server._setEngineStatus('STOPPED'); return; }
                            const ct = (r.headers.get('content-type')||'').toLowerCase();
                            if(ct.includes('application/json')){
                              const j = await r.json();
                              const s = (j.state??j.status??(j.running===true?'RUNNING':(j.running===false?'STOPPED':null))??'STOPPED')+'';
                              cmp.$server._setEngineStatus(s.toUpperCase());
                            }else{
                              const txt = (await r.text()||'').toUpperCase();
                              const st = txt.includes('RUNNING') ? 'RUNNING'
                                       : txt.includes('STARTING') ? 'STARTING'
                                       : txt.includes('STOPPING') ? 'STOPPING'
                                       : txt.includes('KILLED')   ? 'KILLED'
                                       : 'STOPPED';
                              cmp.$server._setEngineStatus(st);
                            }
                          }catch(_){ cmp.$server._setEngineStatus('STOPPED'); }
                        }
                        fetchEngine();
                        if(window.__enginePoll){ clearInterval(window.__enginePoll); }
                        window.__enginePoll = setInterval(fetchEngine, 5000);
                    """, getElement()));

            // Decision quality fallback poll (also drives Feeds perception if SSE isn't flowing)
            getUI().ifPresent(ui -> ui.getPage().executeJs("""
                        const cmp = $0;
                        async function fetchDecision(){
                          try{
                            const r = await fetch('/api/decision/quality', { credentials: 'include' });
                            if(!r.ok) return;
                            const j = await r.json();
                            const raw = Number(j.score ?? j.confidence ?? j.value ?? j.quality ?? 0);
                            cmp.$server._onConfidence(isNaN(raw) ? 0 : raw);
                          }catch(_){}
                        }
                        fetchDecision();
                        if(window.__decisionPoll){ clearInterval(window.__decisionPoll); }
                        window.__decisionPoll = setInterval(fetchDecision, 5000);
                    """, getElement()));

            // --- BACKEND CONNECTIVITY WIRING ---

            // Feeds: use SSE heartbeat to derive OK/WARN/ERR
            getUI().ifPresent(ui -> ui.getPage().executeJs("""
                      const cmp = $0;
                      let lastSse = Date.now();
                    
                      function markSeen(){ lastSse = Date.now(); cmp.$server._setFeedsState('ok'); }
                    
                      // Treat any of these SSE events as a healthy feed
                      ['init','heartbeat','engine.heartbeat','engine.state',
                       'decision.quality','risk.summary','risk.circuit',
                       'order.placed','order.modified','order.cancelled','orders.created',
                       'trade.created','trade.updated','sentiment.update'
                      ].forEach(ev => addEventListener(ev, markSeen));
                    
                      // Degrade only if NOTHING has arrived for a while.
                      if(window.__feedsPoll){ clearInterval(window.__feedsPoll); }
                      window.__feedsPoll = setInterval(()=>{
                        const age = Date.now() - lastSse;
                        if (age > 45000)      cmp.$server._setFeedsState('err');   // >45s -> ERR
                        else if (age > 25000) cmp.$server._setFeedsState('warn');  // >25s -> WARN
                        else                  cmp.$server._setFeedsState('ok');    // fresh -> OK
                      }, 5000);
                    """, getElement()));
        });
    }

    /**
     * Accepts either 0..1 or 0..100 values and shows as percent.
     */
    public void setConfidence(double pctInput) {
        double scaled = (pctInput <= 1.0) ? (pctInput * 100.0) : pctInput;
        int v = (int) Math.max(0, Math.min(100, Math.round(scaled)));

        if (confFill != null) {
            getUI().ifPresent(ui -> ui.getPage().executeJs(
                    "const el=$0;el.style.width=$1+'%';", confFill.getElement(), v));

            if (v < 40) {
                confFill.getStyle().set("background", "linear-gradient(to right, #EF4444, #F87171)");
                confLabel.getStyle().set("color", "#b91c1c").set("background", "#fee2e2");
            } else if (v < 70) {
                confFill.getStyle().set("background", "linear-gradient(to right, #93C5FD, #60A5FA)");
                confLabel.getStyle().set("color", "#1D4ED8").set("background", "#DBEAFE");
            } else {
                confFill.getStyle().set("background", "linear-gradient(to right, " + BRAND + ", " + BRAND_600 + ")");
                confLabel.getStyle().set("color", BRAND_600).set("background", "#ecfdf5");
            }
        }
        if (confLabel != null) confLabel.setText(v + "%");
    }

    public void updateFeedStatus(String status, String state) {
        feedsStatus.setText("Feeds: " + status);
        applyBadgeState(feedsStatus, state);
    }

    /**
     * Update engine pill strictly from backend state.
     */
    private void updateEngineBadge(String state) {
        String s = (state == null ? "STOPPED" : state.trim().toUpperCase());
        engineStatus.setText("Engine: " + ("STOPPED".equals(s) ? "NOT STARTED" : s));
        switch (s) {
            case "RUNNING":
                applyBadgeState(engineStatus, "ok");
                break;
            case "STARTING":
            case "STOPPING":
                applyBadgeState(engineStatus, "warn");
                break;
            case "KILLED":
            case "STOPPED":
            default:
                applyBadgeState(engineStatus, "err");
                break;
        }
    }

    // ---- Events/bridges ----
    public static class ModeChangedEvent extends ComponentEvent<Component> {
        private final boolean sandbox;

        public ModeChangedEvent(Component source, boolean fromClient, boolean sandbox) {
            super(source, fromClient);
            this.sandbox = sandbox;
        }

        public boolean isSandbox() {
            return sandbox;
        }
    }

    private void styleBtn(Button btn, String bgColor, String textColor) {
        btn.getStyle()
                .set("background", bgColor).set("color", textColor)
                .set("border", "none").set("font-weight", "700").set("font-size", "13px")
                .set("border-radius", "10px").set("height", "36px").set("padding", "6px 12px")
                .set("box-shadow", "0 1px 3px rgba(0,0,0,0.06)")
                .set("transition", "box-shadow .2s cubic-bezier(.4,0,.2,1), transform .2s");
        btn.getElement().getStyle().set("--lumo-space-s", "6px");
    }

    private HorizontalLayout compactGroup(Component... items) {
        HorizontalLayout g = new HorizontalLayout(items);
        g.addClassName("compact-group");
        g.setPadding(false);
        g.setSpacing(false);
        g.setAlignItems(FlexComponent.Alignment.CENTER);
        g.getStyle()
                .set("display", "flex")
                .set("gap", "16px")
                .set("flex", "1 1 0")
                .set("background", "rgba(255,255,255,0.7)")
                .set("border-radius", "14px")
                .set("padding", "8px 12px")
                .set("box-shadow", "0 2px 8px rgba(59,130,246,0.10)")
                .set("height", "100%")
                .set("box-sizing", "border-box")
                .set("overflow", "hidden");
        return g;
    }

    private HorizontalLayout growGroup(Component item) {
        HorizontalLayout g = new HorizontalLayout(item);
        g.addClassName("grow-group");
        g.setPadding(false);
        g.setSpacing(false);
        g.setAlignItems(Alignment.CENTER);
        g.getStyle()
                .set("display", "flex")
                .set("gap", "12px")
                .set("flex", "1 1 0")
                .set("min-width", "0")
                .set("background", "rgba(255,255,255,0.7)")
                .set("border-radius", "14px")
                .set("padding", "10px 16px")
                .set("box-shadow", "0 2px 8px rgba(59,130,246,0.10)")
                .set("height", "100%")
                .set("box-sizing", "border-box")
                .set("overflow", "hidden");
        return g;
    }

    private void forceSectionSizing(HorizontalLayout g) {
        g.setHeightFull();
        g.getStyle().set("min-height", "56px").set("height", "56px");
    }

    private Span badge(String text, String state) {
        Span s = new Span(text);
        s.addClassName("status-badge");
        s.getStyle()
                .set("padding", "6px 10px")
                .set("border-radius", "999px")
                .set("font-weight", "700")
                .set("font-size", "0.85rem")
                .set("line-height", "1")
                .set("white-space", "nowrap")
                .set("background", "rgba(255,255,255,0.85)")
                .set("border", "1.5px solid " + BORDER)
                .set("color", MUTED)
                .set("box-shadow", "0 2px 8px rgba(32,192,106,0.08)")
                .set("transition", "all .25s cubic-bezier(.4,0,.2,1)");
        applyBadgeState(s, state);
        return s;
    }

    private Div field(String label, Component content) {
        Span lab = new Span(label.toUpperCase());
        lab.addClassName("field-label");
        lab.getStyle()
                .set("color", MUTED)
                .set("font-weight", "700")
                .set("font-size", "0.85em")
                .set("letter-spacing", ".04em")
                .set("white-space", "nowrap");
        Div wrap = new Div(lab, content);
        wrap.addClassName("field-container");
        wrap.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("gap", "12px")
                .set("background", "rgba(255,255,255,0.7)")
                .set("border", "1.5px solid " + BORDER)
                .set("padding", "8px 12px")
                .set("border-radius", "12px")
                .set("box-shadow", "0 2px 8px rgba(32,192,106,0.08)")
                .set("transition", "all .2s cubic-bezier(.4,0,.2,1)")
                .set("flex", "1 1 0")
                .set("height", "100%")
                .set("box-sizing", "border-box");
        return wrap;
    }

    private void applyBadgeState(Span badge, String state) {
        badge.getStyle().set("background", SURFACE).set("border", "1px solid " + BORDER).set("color", MUTED);
        if ("ok".equals(state)) {
            badge.getStyle()
                    .set("color", BRAND_600).set("border-color", BRAND_200)
                    .set("background", "rgba(0,200,83,0.08)").set("box-shadow", "0 1px 3px rgba(0,200,83,0.1)");
        } else if ("warn".equals(state)) {
            // BLUE (no orange)
            badge.getStyle()
                    .set("color", "#1D4ED8").set("border-color", "#BFDBFE")
                    .set("background", "#DBEAFE").set("box-shadow", "0 1px 3px rgba(59,130,246,0.10)");
        } else if ("err".equals(state)) {
            badge.getStyle()
                    .set("color", "#b91c1c").set("border-color", "#fecaca")
                    .set("background", "#fff1f2").set("box-shadow", "0 1px 3px rgba(239,68,68,0.1)");
        }
    }

    private void showRippleEffect(Button button) {
        getUI().ifPresent(ui -> ui.getPage().executeJs(
                "const btn=$0;const r=document.createElement('span');r.classList.add('ripple-effect');" +
                        "const rect=btn.getBoundingClientRect();const sz=Math.max(rect.width,rect.height);" +
                        "r.style.width=r.style.height=sz+'px';r.style.left=(rect.width/2-sz/2)+'px';" +
                        "r.style.top=(rect.height/2-sz/2)+'px';r.style.position='absolute';r.style.borderRadius='50%';" +
                        "r.style.transform='scale(0)';r.style.animation='ripple .6s linear';r.style.backgroundColor='rgba(255,255,255,.3)';" +
                        "btn.style.overflow='hidden';btn.style.position='relative';btn.appendChild(r);setTimeout(()=>r.remove(),600);" +
                        "if(!document.querySelector('style#ripple-style')){const s=document.createElement('style');s.id='ripple-style';" +
                        "s.textContent='@keyframes ripple { to { transform: scale(4); opacity: 0; } }';document.head.appendChild(s);} ",
                button.getElement()
        ));
    }

    // -------- Client-callables from JS --------
    @ClientCallable
    private void _onConfidence(double s) {
        setConfidence(s);
    }

    @ClientCallable
    public void _setEngineStatus(String status) {
        updateEngineBadge(status);
    }

    @ClientCallable
    private void _setFeedsState(String state) {
        String st = normalizeState(state);
        updateFeedStatus(labelFor(st), st);
    }

    private String normalizeState(String raw) {
        if (raw == null) return "err";
        String s = raw.trim().toLowerCase();
        if (!"ok".equals(s) && !"warn".equals(s) && !"err".equals(s)) return "err";
        return s;
    }

    private String labelFor(String st) {
        return "ok".equals(st) ? "OK" : ("warn".equals(st) ? "WARN" : "ERR");
    }
}
