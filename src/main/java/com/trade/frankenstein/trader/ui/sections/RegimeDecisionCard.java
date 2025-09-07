package com.trade.frankenstein.trader.ui.sections;

import com.vaadin.flow.component.*;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.shared.Registration;

/**
 * Regime & Decision card that mirrors the HTML and reacts to global "confidence" updates.
 * - Title top padding reduced
 * - "Range" → "Trend", blue dot
 * - Ring shows integer number only (no % symbol in the middle)
 * - Header mini progress (140px) reflects confidence percent
 * - Tag bar with RR, Slippage, Throttle
 * - Bulleted reasons list
 * <p>
 * Listens for a custom UI event "confidence-changed" (Integer payload 0..100).
 * To fire from another component:
 * <p>
 * ComponentUtil.fireEvent(UI.getCurrent(),
 * new RegimeDecisionCard.ConfidenceChangedEvent(UI.getCurrent(), true, 72));
 */
public class RegimeDecisionCard extends Div {

    // Header: title + (trend chip + mini progress)
    private final Span title = new Span("Regime & Decision");
    private final Span trendChip = new Span("Trend");
    private final Div progressTrack = new Div(); // fixed width container (140px)
    private final Div progressFill = new Div();  // fills by width %

    // Ring with center number
    private final Div ring = new Div();
    private final Span ringVal = new Span("72");

    // Tags (RR, Slip, Throttle)
    private final Span rrTag = pill("RR 2.1");
    private final Span slipTag = pill("Slip est 0.03%");
    private final Span thrTag = pill("Throttle OK");

    // Reasons
    private final UnorderedList reasons = new UnorderedList();

    private int confidence = 72;
    private Registration busReg;

    public RegimeDecisionCard() {
        addClassName("tf-card");
        getStyle()
                .set("background", "var(--card-bg, #fff)")
                .set("border", "1px solid var(--card-border, #E6E8F0)")
                .set("border-radius", "12px")
                .set("padding", "12px 16px 16px")
                .set("box-shadow", "var(--card-shadow, 0 1px 2px rgba(0,0,0,0.04))");

        // ===== Header =====
        title.getStyle()
                .set("font-weight", "700")
                .set("font-size", "16px")
                .set("color", "#111827")
                .set("padding-bottom", "8px")
                .set("display", "block");

        Div hdLeft = new Div(title);
        hdLeft.getStyle().set("display", "flex").set("align-items", "center");

        // Trend chip
        trendChip.getStyle()
                .set("display", "inline-flex")
                .set("align-items", "center")
                .set("gap", "6px")
                .set("font-size", "12px")
                .set("font-weight", "600")
                .set("color", "#111827");
        Div dot = new Div();
        dot.getStyle()
                .set("width", "8px").set("height", "8px")
                .set("border-radius", "999px")
                .set("background", "#3b82f6");
        trendChip.addComponentAsFirst(dot);

        // 140px progress track/fill
        progressTrack.getStyle()
                .set("width", "140px")
                .set("height", "6px")
                .set("border-radius", "999px")
                .set("background", "#f3f4f6")
                .set("overflow", "hidden");
        progressFill.getStyle()
                .set("height", "100%")
                .set("background", "#22c55e")
                .set("width", confidence + "%");

        HorizontalLayout hdRight = new HorizontalLayout(trendChip, progressTrack);
        hdRight.setSpacing(true);
        hdRight.setAlignItems(FlexComponent.Alignment.CENTER);
        hdRight.getStyle().set("gap", "12px");

        HorizontalLayout header = new HorizontalLayout(hdLeft, hdRight);
        header.setWidthFull();
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.getStyle().set("padding-top", "2px").set("padding-bottom", "4px");

        // ===== Body =====
        // Ring
        ring.getStyle()
                .set("width", "96px").set("height", "96px")
                .set("border-radius", "999px")
                .set("background",
                        "radial-gradient(farthest-side, #fff 70%, transparent 72%) top/100% 100%," +
                                "conic-gradient(#22c55e " + confidence + "%, #e5e7eb 0)")
                .set("display", "grid").set("place-items", "center");
        ringVal.getStyle()
                .set("font-size", "26px")
                .set("font-weight", "800")
                .set("color", "#111827");
        ring.add(ringVal);

        // Bullet reasons
        reasons.getStyle().set("margin", "0").set("padding-left", "18px").set("color", "#374151");
        reasons.add(li("ADX OK & ATR% high"));
        reasons.add(li("Donchian(20) breakout with 65th percentile volume"));
        reasons.add(li("News neutral → tech weighted"));

        // Tags bar
        HorizontalLayout tags = new HorizontalLayout(rrTag, slipTag, thrTag);
        tags.getStyle().set("gap", "8px").set("margin-top", "8px").set("flex-wrap", "wrap");

        // Left text block (reasons + tags)
        VerticalLayout textBlock = new VerticalLayout(reasons, tags);
        textBlock.setPadding(false);
        textBlock.setSpacing(false);
        textBlock.getStyle().set("gap", "8px");

        HorizontalLayout body = new HorizontalLayout(ring, textBlock);
        body.setWidthFull();
        body.setAlignItems(FlexComponent.Alignment.CENTER);
        body.getStyle().set("gap", "16px");

        // Build
        add(header, body);

        // Listen to cross-app confidence updates
        UI.getCurrent().getElement().addEventListener("confidence-changed", e -> {
            try {
                confidence = Integer.parseInt(e.getEventData().getString("event.detail"));
                confidence = Math.max(0, Math.min(100, confidence));
                updateVisuals();
            } catch (Exception ignored) {
            }
        }).addEventData("event.detail");

        // Also support programmatic registration (server-side event)
        busReg = ComponentUtil.addListener(UI.getCurrent(), ConfidenceChangedEvent.class, ev -> {
            confidence = Math.max(0, Math.min(100, ev.getConfidence()));
            updateVisuals();
        });

        // Sync initial state
        updateVisuals();
        progressTrack.add(progressFill);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (busReg != null) busReg.remove();
        super.onDetach(detachEvent);
    }

    private static Span pill(String text) {
        Span p = new Span(text);
        p.getStyle()
                .set("padding", "4px 8px")
                .set("border-radius", "999px")
                .set("font-size", "12px")
                .set("font-weight", "700")
                .set("border", "1px solid #E5E7EB")
                .set("color", "#374151")
                .set("background", "#fff");
        return p;
    }

    private static ListItem li(String t) {
        ListItem li = new ListItem(t);
        li.getStyle().set("margin", "0");
        return li;
    }

    /**
     * Custom server-side event to change confidence.
     */
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

    /**
     * Sync ring sweep, number text, and header progress fill.
     */
    private void updateVisuals() {
        // Update the ring sweep (conic-gradient) and the number (no % symbol)
        ring.getStyle().set("background",
                "radial-gradient(farthest-side, #fff 70%, transparent 72%) top/100% 100%, " +
                        "conic-gradient(#22c55e " + confidence + "%, #e5e7eb 0)");
        ringVal.setText(String.valueOf(confidence)); // no percent sign

        // Update the header progress fill width (container is 140px wide)
        progressFill.getStyle().set("width", confidence + "%");
    }
}
