package com.trade.frankenstein.trader.ui.header;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;

public class ControlsBar extends HorizontalLayout {

    // --- Colors / tokens
    private static final String BRAND = "#20C06A";
    private static final String BRAND_600 = "#18A557";
    private static final String BRAND_200 = "#CFF6E1";
    private static final String BORDER = "#E6E8F0";
    private static final String DANGER = "#EF4444";
    private static final String INFO = "#0ea5e9";
    private static final String MUTED = "#6B7280";
    private static final String SURFACE = "#FFFFFF";
    private static final String SHADOW = "0 6px 16px rgba(16,24,40,.05)";

    private final RadioButtonGroup<String> modeGroup = new RadioButtonGroup<>();

    // Read-only confidence meter parts
    private Div confFill;
    private Span confLabel;

    public ControlsBar() {
        setWidthFull();
        setPadding(false);
        setSpacing(false);
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.START);
        getStyle()
                .set("position", "sticky")
                .set("display", "flex")
                .set("flex-wrap", "nowrap")
                .set("gap", "8px")
                .set("padding", "8px 16px")
                .set("box-sizing", "border-box");

        // === Buttons ===
        Button startBtn = new Button("Start");
        startBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        styleBtn(startBtn, BRAND, "#fff");

        Button stopBtn = new Button("Stop");
        stopBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        styleBtn(stopBtn, INFO, "#fff");

        Button killBtn = new Button("Kill Switch");
        killBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        styleBtn(killBtn, DANGER, "#fff");
        killBtn.addClickListener(e -> confirmKill());

        HorizontalLayout actions = compactGroup(startBtn, stopBtn, killBtn);

        // === Mode radios (Live / Sandbox) ===
        modeGroup.setItems("Live", "Sandbox");
        modeGroup.setValue("Sandbox");
        modeGroup.getStyle()
                .set("padding", "4px 6px")
                .set("background", SURFACE)
                .set("border", "1px solid " + BORDER)
                .set("border-radius", "8px")
                .set("color", MUTED)
                .set("font-weight", "600");
        modeGroup.addValueChangeListener(e -> {
            boolean sandbox = "Sandbox".equals(e.getValue());
            // Show Kill only for Live
            killBtn.setVisible(!sandbox);

            UI ui = UI.getCurrent();
            if (ui != null) {
                ComponentUtil.fireEvent(ui, new ModeChangedEvent(this, false, sandbox));
            }
        });
        HorizontalLayout toggles = compactGroup(modeGroup);

        // === Confidence (read-only meter) ===
        Div confMeter = new Div();
        confFill = new Div();
        confLabel = new Span("--%");
        confMeter.add(confFill);

        confMeter.getStyle()
                .set("width", "140px")
                .set("height", "8px")
                .set("border", "1px solid " + BORDER)
                .set("border-radius", "999px")
                .set("background", SURFACE)
                .set("overflow", "hidden");
        confFill.getStyle()
                .set("height", "100%")
                .set("width", "0%")
                .set("background", BRAND)
                .set("border-radius", "999px 0 0 999px")
                .set("transition", "width 120ms ease");

        Div confWrap = field("Confidence", new HorizontalLayout(confMeter, confLabel));
        ((HorizontalLayout) confWrap.getComponentAt(1)).setPadding(false);
        ((HorizontalLayout) confWrap.getComponentAt(1)).setSpacing(false);
        ((HorizontalLayout) confWrap.getComponentAt(1)).setAlignItems(Alignment.CENTER);
        confWrap.getComponentAt(1).getStyle().set("gap", "8px");

        HorizontalLayout confidence = growGroup(confWrap);

        // === Status badges ===
        Span feedsOk = badge("Feeds: OK", "ok");
        Span ordersOk = badge("Orders: OK", "ok");
        Span ttl = badge("Upstox Token TTL: 14:59", null);
        HorizontalLayout badges = compactGroup(feedsOk, ordersOk, ttl);

        add(actions, toggles, confidence, badges);
        setFlexGrow(0, actions, toggles, badges);
        setFlexGrow(1, confidence);

        // Fire initial state AFTER attach so listeners (e.g., header) receive it
        addAttachListener(e -> {
            UI ui = e.getUI();
            boolean sandbox = "Sandbox".equals(modeGroup.getValue());
            killBtn.setVisible(!sandbox);
            if (ui != null) {
                ComponentUtil.fireEvent(ui, new ModeChangedEvent(this, false, sandbox));
            }
        });
    }

    // Public API: let backend/UI set calibrated confidence 0..100
    public void setConfidence(double pct) {
        int v = (int) Math.max(0, Math.min(100, Math.round(pct)));
        if (confFill != null) confFill.getStyle().set("width", v + "%");
        if (confLabel != null) confLabel.setText(v + "%");
    }

    // === Event to broadcast mode changes ===
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

    // === Helpers ===
    private HorizontalLayout compactGroup(Component... items) {
        HorizontalLayout g = new HorizontalLayout(items);
        g.setPadding(false);
        g.setSpacing(false);
        g.setAlignItems(FlexComponent.Alignment.CENTER);
        g.getStyle()
                .set("display", "flex")
                .set("gap", "8px")
                .set("flex", "0 0 auto");
        return g;
    }

    private HorizontalLayout growGroup(Component item) {
        HorizontalLayout g = new HorizontalLayout(item);
        g.setPadding(false);
        g.setSpacing(false);
        g.setAlignItems(Alignment.STRETCH);
        g.getStyle()
                .set("display", "flex")
                .set("gap", "8px")
                .set("flex", "1 1 288px")
                .set("min-width", "0");
        return g;
    }

    private void styleBtn(Button btn, String bg, String fg) {
        btn.getStyle()
                .set("--lumo-primary-color", bg)
                .set("--lumo-primary-contrast-color", fg)
                .set("--lumo-button-size", "28px")
                .set("--lumo-border-radius-m", "8px")
                .set("box-shadow", SHADOW)
                .set("background", bg)
                .set("color", fg)
                .set("font-weight", "600")
                .set("border", "none")
                .set("padding", "0 10px");
    }

    private Div field(String label, Component content) {
        Span lab = new Span(label);
        lab.getStyle().set("color", MUTED).set("font-weight", "600");
        Div wrap = new Div(lab, content);
        wrap.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("gap", "6px")
                .set("background", SURFACE)
                .set("border", "1px solid " + BORDER)
                .set("padding", "6px 8px")
                .set("border-radius", "8px")
                .set("flex", "0 0 auto");
        return wrap;
    }

    private Span badge(String text, String state) {
        Span s = new Span(text);
        s.getStyle()
                .set("padding", "5px 8px")
                .set("border-radius", "999px")
                .set("font-weight", "600")
                .set("background", SURFACE)
                .set("border", "1px solid " + BORDER)
                .set("color", MUTED);
        if ("ok".equals(state)) {
            s.getStyle().set("color", BRAND_600).set("border-color", BRAND_200);
        } else if ("warn".equals(state)) {
            s.getStyle().set("color", "#b45309").set("border-color", "#fde68a").set("background", "#fffbeb");
        } else if ("err".equals(state)) {
            s.getStyle().set("color", "#b91c1c").set("border-color", "#fecaca").set("background", "#fff1f2");
        }
        return s;
    }

    private void confirmKill() {
        Dialog d = new Dialog();
        d.setHeaderTitle("Confirm Kill Switch");
        Span body = new Span("This immediately halts live execution. Are you sure?");
        Button cancel = new Button("Cancel", e -> d.close());
        Button ok = new Button("Confirm Kill", e -> {
            // TODO: wire your kill handler
            d.close();
        });
        ok.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout actions = new HorizontalLayout(cancel, ok);
        actions.setSpacing(true);
        actions.setPadding(false);
        actions.setJustifyContentMode(JustifyContentMode.END);

        d.add(body);
        d.getFooter().add(actions);
        d.open();
    }
}
