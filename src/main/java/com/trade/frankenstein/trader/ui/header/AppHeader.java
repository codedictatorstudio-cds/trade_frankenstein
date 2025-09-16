package com.trade.frankenstein.trader.ui.header;

import com.trade.frankenstein.trader.ui.bridge.EngineApiClient;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AppHeader — Premium blue/green, no engine controls, no orange/yellow.
 */
public class AppHeader extends Div {

    private static final String BLUE_50 = "#EFF6FF";
    private static final String BLUE_100 = "#DBEAFE";
    private static final String BLUE_TEXT = "#3B82F6";
    private static final String GREEN = "#00C853";
    private static final String BORDER = "#E6E8F0";
    private static final String TEXT = "#0B1221";
    private static final String WHITE = "#FFFFFF";

    private static final String HUD_FS = "12px";
    private static final String HUD_PAD = "7px 11px";
    private static final String OUTER_PAD = "10px 16px";

    private Span modeValue;
    private HorizontalLayout lastTickBadgeRef;
    private HorizontalLayout brokerBadgeRef;

    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    @SuppressWarnings("unused")
    private final EngineApiClient apiClient; // kept if you use it elsewhere

    public AppHeader(EngineApiClient apiClient) {
        this.apiClient = apiClient;
        setupHeaderStyle();
        createHeaderContent();
        updateLastTickTime();
    }

    private void setupHeaderStyle() {
        setWidthFull();
        getStyle()
                .set("position", "sticky")
                .set("top", "0")
                .set("z-index", "10")
                .set("background", "linear-gradient(135deg, " + BLUE_50 + ", " + BLUE_100 + ")")
                .set("backdrop-filter", "saturate(140%) blur(6px)")
                .set("-webkit-backdrop-filter", "saturate(140%) blur(6px)")
                .set("box-shadow", "0 2px 12px rgba(37,99,235,0.12)")
                .set("border-bottom", "1px solid rgba(191,219,254,0.9)");
    }

    private void createHeaderContent() {
        Div inner = new Div();
        inner.setWidthFull();
        inner.getStyle().set("max-width", "1280px").set("margin", "0 auto").set("padding", OUTER_PAD).set("color", TEXT);

        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setPadding(false);
        row.setSpacing(false);
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        HorizontalLayout left = createBrandSection();
        HorizontalLayout right = createRightSection();

        row.add(left, right);
        inner.add(row);
        add(inner);
    }

    private HorizontalLayout createBrandSection() {
        HorizontalLayout brand = new HorizontalLayout();
        brand.setPadding(false);
        brand.setSpacing(false);
        brand.setAlignItems(FlexComponent.Alignment.CENTER);
        brand.getStyle().set("display", "flex").set("gap", "10px");

        Div logo = new Div(new Span("TF"));
        logo.getStyle().set("width", "28px").set("height", "28px").set("border-radius", "10px")
                .set("background", "linear-gradient(135deg,#00C853,#00B84E)")
                .set("display", "grid").set("place-items", "center")
                .set("color", WHITE).set("font-weight", "900").set("font-size", "14px")
                .set("box-shadow", "0 2px 4px rgba(0,0,0,0.1)").set("cursor", "pointer");
        logo.getElement().addEventListener("click", e -> UI.getCurrent().navigate(""));

        Span brandText = new Span("TradeFrankenstein");
        brandText.getStyle().set("font-size", "16px").set("font-weight", "800").set("letter-spacing", "-0.3px");

        brand.add(logo, brandText);
        return brand;
    }

    private HorizontalLayout createRightSection() {
        HorizontalLayout right = new HorizontalLayout();
        right.setPadding(false);
        right.setSpacing(false);
        right.setAlignItems(FlexComponent.Alignment.CENTER);
        right.getStyle().set("display", "flex").set("gap", "10px");

        HorizontalLayout hud = new HorizontalLayout();
        hud.setPadding(false);
        hud.setSpacing(false);
        hud.setAlignItems(FlexComponent.Alignment.CENTER);
        hud.getStyle().set("display", "flex").set("gap", "10px");

        HorizontalLayout mode = modeBadge("SANDBOX");  // default SANDBOX
        brokerBadgeRef = badge("Broker:", "OK", "ok");
        lastTickBadgeRef = badge("Last Tick:", "--:--:--", "base");

        hud.add(mode, brokerBadgeRef, lastTickBadgeRef);
        right.add(hud);
        return right;
    }

    private HorizontalLayout modeBadge(String value) {
        Span label = new Span("Mode:");
        label.getStyle().set("font-weight", "800").set("color", GREEN).set("font-size", HUD_FS).set("line-height", "1").set("white-space", "nowrap");

        modeValue = new Span(value);
        modeValue.getStyle().set("font-weight", "800").set("margin-left", "6px").set("font-size", HUD_FS).set("line-height", "1")
                .set("white-space", "nowrap").set("color", BLUE_TEXT);

        HorizontalLayout pill = new HorizontalLayout(label, modeValue);
        pill.setPadding(false);
        pill.setSpacing(false);
        pill.setAlignItems(FlexComponent.Alignment.CENTER);
        pill.getStyle().set("display", "inline-flex").set("white-space", "nowrap").set("min-width", "max-content")
                .set("background", "linear-gradient(135deg, #F9FAFB, #F3F4F6)")
                .set("border", "1px solid " + BORDER).set("box-shadow", "0 1px 3px rgba(0,0,0,0.06)")
                .set("border-radius", "999px").set("padding", HUD_PAD);
        return pill;
    }

    private HorizontalLayout badge(String label, String value, String type) {
        Span l = new Span(label);
        l.getStyle().set("font-weight", "800").set("color", GREEN).set("font-size", HUD_FS).set("line-height", "1").set("white-space", "nowrap");
        Span v = new Span(value);
        v.getStyle().set("font-weight", "800").set("margin-left", "6px").set("font-size", HUD_FS).set("line-height", "1").set("white-space", "nowrap").set("color", BLUE_TEXT);

        HorizontalLayout pill = new HorizontalLayout(l, v);
        pill.setPadding(false);
        pill.setSpacing(false);
        pill.setAlignItems(FlexComponent.Alignment.CENTER);
        pill.getStyle().set("display", "inline-flex").set("white-space", "nowrap").set("min-width", "max-content")
                .set("background", WHITE).set("padding", HUD_PAD).set("border-radius", "999px")
                .set("border", "1px solid " + BORDER).set("box-shadow", "0 1px 3px rgba(0,0,0,0.06)");

        if ("ok".equals(type)) {
            v.getStyle().set("color", "#00B84E");
            pill.getStyle().set("border", "1px solid #D8FAE6").set("background", "rgba(0,200,83,0.06)");
        } else if ("warn".equals(type)) {
            v.getStyle().set("color", "#2563EB");
            pill.getStyle().set("border", "1px solid " + BLUE_100).set("background", BLUE_50);
        } else if ("err".equals(type)) {
            v.getStyle().set("color", "#DC2626");
            pill.getStyle().set("border", "1px solid #FECACA").set("background", "#FFF1F2");
        } else if (label.contains("Last Tick")) {
            v.getStyle().set("color", BLUE_TEXT);
            pill.getStyle().set("background", "linear-gradient(135deg, " + BLUE_50 + ", " + BLUE_100 + ")")
                    .set("border", "1px solid " + BLUE_100);
        }
        return pill;
    }

    public void setLastTickText(String hhmmss) {
        if (lastTickBadgeRef != null && lastTickBadgeRef.getComponentCount() >= 2) {
            ((Span) lastTickBadgeRef.getComponentAt(1)).setText(hhmmss);
        }
    }

    public void updateLastTickTime() {
        setLastTickText(LocalDateTime.now().format(timeFormatter));
    }

    // Simple bridge for external events if you still dispatch them:
    @ClientCallable
    private void _onTick() {
        updateLastTickTime();
    }

    // Mode bridge from ControlsBar (kept)
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        ComponentUtil.addListener(
                attachEvent.getUI(),
                ControlsBar.ModeChangedEvent.class,
                ev -> setMode(ev.isSandbox() ? "SANDBOX" : "LIVE")
        );
        setMode("SANDBOX");
    }

    private void setMode(String modeText) {
        if (modeValue == null) return;
        modeValue.setText(modeText);
        modeValue.getStyle().set("color", BLUE_TEXT);
    }
}
