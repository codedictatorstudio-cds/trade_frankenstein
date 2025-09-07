package com.trade.frankenstein.trader.ui.sections;

import com.trade.frankenstein.trader.ui.shared.CardSection;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

import java.text.NumberFormat;
import java.util.Locale;

import static com.trade.frankenstein.trader.ui.shared.GridSpan.forceSingleLineFit;

/**
 * Risk Panel — matches RegimeDecisionCard size, aligned, inline label+bar, no badge.
 * UI-v2: lighter padding/shadows, themeable vars, consistent setters.
 */
public class RiskPanelCard extends CardSection {

    // === Spacing (lighter) ===
    private static final String PAD_X = "16px";   // left/right inner space
    private static final String PAD_Y = "0";
    private static final String GAP_BLOCK = "8px";
    private static final String GAP_GRID = "8px";
    private static final String PAD_KPI = "8px";

    // === Themeable card vars with fallbacks ===
    private static final String CARD_BG = "var(--card-bg, #FFFFFF)";
    private static final String CARD_BORDER = "var(--card-border, 1px solid #E6E8F0)";
    private static final String KPI_BG = "var(--kpi-bg, #FFFFFF)";
    private static final String TRACK_BG = "var(--track-bg, #f3f4f6)";
    private static final String BAR_ORANGE = "var(--risk-orange, #f59e0b)";
    private static final String BAR_CYAN = "var(--risk-cyan, #06b6d4)";
    private static final String TEXT_MUTED = "var(--text-muted, #6B7280)";
    private static final String TEXT_MAIN = "var(--text-main, #111827)";

    // === State ===
    private double budgetLeft = 8420;
    private int lotsUsed = 4;
    private int lotsCap = 6;
    private double dailyLossPct = 68;     // 0..100
    private double ordersPerMinPct = 48;  // 0..100

    // Widgets
    private final Span budgetValue = new Span();
    private final Span lotsValue = new Span();
    private final Div lossBarInner = new Div();
    private final Div rateBarInner = new Div();

    public RiskPanelCard() {
        super("Risk Panel");

        // Card chrome
        getStyle()
                .set("background", CARD_BG)
                .set("border", CARD_BORDER)
                .set("border-radius", "12px")
                .set("padding", PAD_Y + " " + PAD_X)
                .set("box-shadow", "var(--card-shadow, 0 1px 2px rgba(0,0,0,0.04))");

        // Two-column grid (small → stack)
        Div grid = new Div();
        grid.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "1fr 1fr")
                .set("gap", GAP_GRID)
                .set("align-items", "start");
        add(grid);

        // === Top KPIs ===
        Div kpiBudget = kpi("Risk Budget Left (₹)", budgetValue);
        Div kpiLots = kpi("Lots Used", lotsValue);
        grid.add(kpiBudget, kpiLots);

        // === Bars ===
        grid.add(labeledBar("Daily Loss", lossBarInner, dailyLossPct));
        grid.add(labeledBar("Orders/Minute", rateBarInner, ordersPerMinPct));

        // Initial state reflect
        setBudgetLeft(budgetLeft);
        setLots(lotsUsed, lotsCap);
        setDailyLossPercent(dailyLossPct);
        setOrdersPerMinutePercent(ordersPerMinPct);
    }

    // --- Public setters for wiring ---
    public void setBudgetLeft(double inr) {
        this.budgetLeft = inr;
        budgetValue.setText(formatINR(inr));
    }

    public void setLots(int used, int cap) {
        this.lotsUsed = used;
        this.lotsCap = cap;
        lotsValue.setText(used + " / " + cap);
    }

    public void setDailyLossPercent(double pct) {
        this.dailyLossPct = percentClamp(pct);
        setBarWidth(lossBarInner, dailyLossPct);
    }

    public void setOrdersPerMinutePercent(double pct) {
        this.ordersPerMinPct = percentClamp(pct);
        setBarWidth(rateBarInner, ordersPerMinPct);
    }

    // --- UI pieces ---
    private Div kpi(String label, Span valueEl) {
        Div wrap = new Div();
        wrap.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("gap", "6px")
                .set("padding", PAD_KPI)
                .set("background", KPI_BG)
                .set("border-radius", "8px")
                .set("border", "1px solid #E6E8F0");

        Span lab = new Span(label);
        lab.getStyle()
                .set("font-size", "12px")
                .set("font-weight", "600")
                .set("color", TEXT_MUTED);
        valueEl.getStyle()
                .set("font-size", "18px")
                .set("font-weight", "800")
                .set("color", TEXT_MAIN);

        wrap.add(lab, valueEl);
        return wrap;
    }

    private Div labeledBar(String label, Div innerBar, double initialPercent) {
        Div wrap = new Div();
        wrap.getStyle().set("display", "flex").set("flex-direction", "column");

        Span lab = new Span(label);
        lab.getStyle()
                .set("font-size", "12px")
                .set("font-weight", "600")
                .set("color", TEXT_MUTED);

        Div outer = new Div();
        outer.getStyle()
                .set("height", "8px")
                .set("background", TRACK_BG)
                .set("border-radius", "999px")
                .set("overflow", "hidden")
                .set("margin-top", "6px");
        forceSingleLineFit(outer);

        innerBar.getStyle()
                .set("height", "100%")
                .set("width", Math.round(percentClamp(initialPercent)) + "%")
                .set("transition", "width .25s ease");
        // color per-line
        if ("Daily Loss".equals(label)) innerBar.getStyle().set("background", BAR_ORANGE);
        else innerBar.getStyle().set("background", BAR_CYAN);

        outer.add(innerBar);
        wrap.add(lab, outer);
        return wrap;
    }

    // --- helpers ---
    private void setBarWidth(Div inner, double percent) {
        int rounded = (int) Math.round(percentClamp(percent));
        inner.getStyle().set("width", rounded + "%");
    }

    private double percentClamp(double p) {
        return Math.max(0, Math.min(100, p));
    }

    private String formatINR(double amount) {
        Locale enIN = new Locale("en", "IN");
        NumberFormat nf = NumberFormat.getCurrencyInstance(enIN);
        nf.setMaximumFractionDigits(0);
        nf.setMinimumFractionDigits(0);
        return nf.format(amount);
    }
}
