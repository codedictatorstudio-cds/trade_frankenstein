package com.trade.frankenstein.trader.ui.sections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.ui.bridge.ApiClient;
import com.trade.frankenstein.trader.ui.shared.CardSection;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.function.Function;

import static com.trade.frankenstein.trader.ui.shared.GridSpan.forceSingleLineFit;

public class RiskPanelCard extends CardSection {

    // === Networking / JSON ===
    private static final ObjectMapper M = ApiClient.json();
    private static final String RISK_SUMMARY_API = "/api/risk/summary";

    // === Styling constants ===
    private static final String PREMIUM_GRADIENT = "linear-gradient(135deg, #1a237e, #283593)";
    private static final String PREMIUM_BOX_SHADOW = "0 8px 16px rgba(0, 0, 0, 0.1)";

    // === Model (no static demo values) ===
    private double budgetLeft = Double.NaN;
    private int lotsUsed = 0;
    private int lotsCap = 0;
    private double dailyLossPct = 0;
    private double ordersPerMinPct = 0;

    // === UI parts ===
    private final Span budgetValue = new Span("--");
    private final Span lotsValue = new Span("--");
    private final Div lossBarOuter = new Div();
    private final Div lossBarInner = new Div();
    private final Span lossValueLabel = new Span("--");
    private final Div rateBarOuter = new Div();
    private final Div rateBarInner = new Div();
    private final Span rateValueLabel = new Span("--");

    public RiskPanelCard() {
        super("Risk Management");
        getStyle().set("grid-column", "var(--risk-span, span 4)")
                .set("background", "#FFFFFF")
                .set("box-shadow", PREMIUM_BOX_SHADOW)
                .set("border-radius", "12px")
                .set("overflow", "hidden")
                .set("padding-bottom", "0")
                .set("display", "flex")
                .set("flex-direction", "column");

        // Header with premium gradient
        title.getStyle()
                .set("font-size", "1.2rem")
                .set("font-weight", "600")
                .set("color", "#FFFFFF")
                .set("background", PREMIUM_GRADIENT)
                .set("padding", "16px 20px")
                .set("margin-bottom", "0")
                .set("width", "100%")
                .set("box-sizing", "border-box")
                .set("border-bottom", "none");

        Icon securityIcon = VaadinIcon.SHIELD.create();
        securityIcon.getStyle().set("color", "#FFFFFF").set("margin-right", "8px");
        title.getElement().insertChild(0, securityIcon.getElement());

        // Content grid
        Div contentContainer = new Div();
        contentContainer.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(2, 1fr)")
                .set("gap", "20px")
                .set("padding", "20px")
                .set("flex-grow", "1")
                .set("box-sizing", "border-box")
                .set("width", "100%");

        // KPIs
        Div budget = kpi("Risk Budget Left", budgetValue);
        Div lots = kpi("Lots Used (used/cap)", lotsValue);

        // Bars section
        Div barSection = new Div();
        barSection.getStyle()
                .set("grid-column", "1 / -1")
                .set("display", "grid")
                .set("grid-template-columns", "repeat(2, 1fr)")
                .set("gap", "20px")
                .set("margin-top", "12px");

        buildBar(lossBarOuter, lossBarInner, lossValueLabel, "Daily Loss %");
        buildBar(rateBarOuter, rateBarInner, rateValueLabel, "Orders / Min %");

        barSection.add(lossBarOuter, rateBarOuter);
        contentContainer.add(budget, lots, barSection);
        add(contentContainer);

        // No demo set* calls here — values remain "--" / 0% until real data arrives
    }

    // === Lifecycle ===
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        // 1) Initial fetch
        _pollRisk();

        // 2) SSE hook (push) + fallback polling every 15s
        getUI().ifPresent(ui -> ui.getPage().executeJs("""
                    const cmp = $0;
                    window.addEventListener('risk.summary', e => cmp.$server._onRisk(e.detail));
                    if (!window.__riskPollTimer) {
                      window.__riskPollTimer = setInterval(() => {
                        try { cmp.$server._pollRisk(); } catch (e) {}
                      }, 15000);
                    }
                """, getElement()));
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        getUI().ifPresent(ui -> ui.getPage().executeJs("""
                    if (window.__riskPollTimer) {
                      clearInterval(window.__riskPollTimer);
                      window.__riskPollTimer = null;
                    }
                """));
        super.onDetach(detachEvent);
    }

    // === Public setters used by JSON apply ===
    public void setBudgetLeft(double inr) {
        if (Double.compare(this.budgetLeft, inr) != 0) {
            budgetValue.getElement().getClassList().add("value-change");
            budgetValue.getElement().executeJs("setTimeout(() => this.classList.remove('value-change'), 300);");
        }
        this.budgetLeft = inr;
        budgetValue.setText(formatINR(inr));
    }

    public void setLots(int used, int cap) {
        if (this.lotsUsed != used || this.lotsCap != cap) {
            lotsValue.getElement().getClassList().add("value-change");
            lotsValue.getElement().executeJs("setTimeout(() => this.classList.remove('value-change'), 300);");
        }
        this.lotsUsed = used;
        this.lotsCap = cap;
        lotsValue.setText(used + " / " + cap);
    }

    public void setDailyLossPercent(double pct) {
        this.dailyLossPct = percentClamp(pct);
        setBarWidth(lossBarInner, lossValueLabel, this.dailyLossPct);
    }

    public void setOrdersPerMinutePercent(double pct) {
        this.ordersPerMinPct = percentClamp(pct);
        setBarWidth(rateBarInner, rateValueLabel, this.ordersPerMinPct);
    }

    // === UI helpers ===
    private Div kpi(String label, Span valueEl) {
        Div wrap = new Div();
        wrap.getStyle()
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("justify-content", "center")
                .set("gap", "8px")
                .set("background", "#fafafa")
                .set("border-radius", "12px")
                .set("padding", "16px")
                .set("transition", "all 0.2s ease-in-out")
                .set("box-shadow", "0 2px 4px rgba(0,0,0,0.03)");
        wrap.addClassName("premium-kpi-card");

        wrap.getElement().executeJs(
                "this.addEventListener('mouseenter', () => this.style.transform = 'translateY(-2px)');" +
                        "this.addEventListener('mouseleave', () => this.style.transform = 'translateY(0)');"
        );

        Span lab = new Span(label);
        lab.getStyle()
                .set("color", "#5c6bc0")
                .set("font-weight", "600")
                .set("font-size", "0.9rem")
                .set("letter-spacing", "0.3px");

        valueEl.getStyle()
                .set("font-weight", "700")
                .set("font-size", "1.5rem")
                .set("color", "#1a237e")
                .set("text-shadow", "0 1px 2px rgba(0,0,0,0.05)");

        wrap.add(lab, valueEl);
        forceSingleLineFit(wrap);
        return wrap;
    }

    private void buildBar(Div outer, Div inner, Span valueLabel, String label) {
        outer.addClassName("premium-progress-container");

        Div headerDiv = new Div();
        headerDiv.getStyle()
                .set("display", "flex")
                .set("justify-content", "space-between")
                .set("align-items", "center")
                .set("margin-bottom", "8px");

        Span lab = new Span(label);
        lab.getStyle()
                .set("color", "#5c6bc0")
                .set("font-weight", "600")
                .set("font-size", "0.9rem")
                .set("letter-spacing", "0.3px");

        valueLabel.getStyle()
                .set("color", "#3949ab")
                .set("font-weight", "500")
                .set("font-size", "0.8rem");

        // Reflect width → label text
        inner.getElement().executeJs(
                "const lbl=$0; const obs=new MutationObserver(ms=>ms.forEach(m=>{if(m.attributeName==='style'){lbl.textContent=this.style.width||'0%';}}));" +
                        "obs.observe(this,{attributes:true});", valueLabel.getElement());

        headerDiv.add(lab, valueLabel);
        outer.add(headerDiv);

        Div track = new Div();
        track.getStyle()
                .set("width", "100%")
                .set("height", "10px")
                .set("border-radius", "999px")
                .set("background", "rgba(0,0,0,0.05)")
                .set("overflow", "hidden")
                .set("box-shadow", "inset 0 1px 3px rgba(0,0,0,0.1)");

        inner.getStyle()
                .set("height", "100%")
                .set("background", "linear-gradient(90deg, #3949ab, #5c6bc0)")
                .set("border-radius", "999px")
                .set("width", "0%")
                .set("transition", "width 0.5s ease-out");

        track.add(inner);
        outer.add(track);
        outer.getStyle()
                .set("background", "#fafafa")
                .set("border-radius", "12px")
                .set("padding", "16px")
                .set("box-shadow", "0 2px 4px rgba(0,0,0,0.03)")
                .set("transition", "all 0.2s ease-in-out")
                .set("height", "100%")
                .set("width", "100%")
                .set("box-sizing", "border-box")
                .set("display", "flex")
                .set("flex-direction", "column")
                .set("justify-content", "space-between");
    }

    private void setBarWidth(Div inner, Span valueLabel, double pct) {
        double clamped = Math.max(0, Math.min(100, pct));
        inner.getStyle().set("width", clamped + "%");

        if (clamped > 80) {
            inner.getStyle().set("background", "linear-gradient(90deg, #f44336, #ff5722)");
            valueLabel.getStyle().set("color", "#f44336");
        } else if (clamped > 60) {
            inner.getStyle().set("background", "linear-gradient(90deg, #ff9800, #ffb74d)");
            valueLabel.getStyle().set("color", "#ff9800");
        } else {
            inner.getStyle().set("background", "linear-gradient(90deg, #3949ab, #5c6bc0)");
            valueLabel.getStyle().set("color", "#3949ab");
        }
    }

    private double percentClamp(double p) {
        return Math.max(0, Math.min(100, p));
    }

    private String formatINR(double amount) {
        if (Double.isNaN(amount)) return "--";
        Locale enIN = new Locale("en", "IN");
        NumberFormat nf = NumberFormat.getCurrencyInstance(enIN);
        nf.setMaximumFractionDigits(0);
        nf.setMinimumFractionDigits(0);
        return nf.format(amount);
    }

    private void applyRiskJson(JsonNode root) {
        if (root == null || root.isNull()) return;

        // 1) Unwrap common envelopes
        JsonNode j = root;
        if (j.has("ok") && j.has("data")) j = j.get("data");
        else if (j.has("success") && j.has("value")) j = j.get("value");
        if (j == null || j.isNull()) return;

        // Small helpers (accept number or numeric text)
        Function<JsonNode, Double> asDouble =
                n -> n == null || n.isNull() ? Double.NaN :
                        (n.isNumber() ? n.asDouble() :
                                (n.isTextual() ? safeParseDouble(n.asText(), Double.NaN) : Double.NaN));

        Function<JsonNode, Integer> asInt =
                n -> n == null || n.isNull() ? 0 :
                        (n.isInt() ? n.asInt() :
                                (n.isNumber() ? n.asInt() :
                                        (n.isTextual() ? (int) safeParseDouble(n.asText(), 0) : 0)));

        // 2) Budget Left (camelCase or snake_case)
        Double budget = null;
        if (j.has("riskBudgetLeft")) budget = asDouble.apply(j.get("riskBudgetLeft"));
        else if (j.has("risk_budget_left")) budget = asDouble.apply(j.get("risk_budget_left"));
        if (budget != null && !budget.isNaN()) setBudgetLeft(budget);

        // 3) Lots (support nested {lotsUsed:{used,cap}} or flat lotsUsed/lotsCap; camel & snake)
        if (j.has("lotsUsed") && j.get("lotsUsed").isObject()) {
            JsonNode lu = j.get("lotsUsed");
            int used = asInt.apply(lu.get("used"));
            if (used == 0) used = asInt.apply(lu.get("lots_used"));
            int cap = asInt.apply(lu.get("cap"));
            if (cap == 0) {
                cap = asInt.apply(j.get("lotsCap"));
                if (cap == 0) cap = asInt.apply(j.get("lots_cap"));
            }
            setLots(used, cap);
        } else {
            int used = asInt.apply(j.get("lotsUsed"));
            if (used == 0) used = asInt.apply(j.get("lots_used"));
            int cap = asInt.apply(j.get("lotsCap"));
            if (cap == 0) cap = asInt.apply(j.get("lots_cap"));
            setLots(used, cap);
        }

        // 4) Daily Loss %
        Double dlp = null;
        if (j.has("dailyLossPct")) dlp = asDouble.apply(j.get("dailyLossPct"));
        else if (j.has("daily_loss_pct")) dlp = asDouble.apply(j.get("daily_loss_pct"));
        if (dlp != null) setDailyLossPercent(dlp);

        // 5) Orders / minute %
        Double opm = null;
        if (j.has("ordersPerMinutePct")) opm = asDouble.apply(j.get("ordersPerMinutePct"));
        else if (j.has("ordersPerMinPct")) opm = asDouble.apply(j.get("ordersPerMinPct"));
        else if (j.has("orders_per_minute_pct")) opm = asDouble.apply(j.get("orders_per_minute_pct"));
        else if (j.has("orders_per_min_pct")) opm = asDouble.apply(j.get("orders_per_min_pct"));
        if (opm != null) setOrdersPerMinutePercent(opm);
    }

    // Safe numeric parse for textual nodes
    private static double safeParseDouble(String s, double def) {
        try {
            if (s == null) return def;
            return Double.parseDouble(s.trim());
        } catch (Exception ignore) {
            return def;
        }
    }

    /**
     * Called by SSE handler in the browser with JSON string payload.
     */
    @ClientCallable
    private void _onRisk(String json) {
        try {
            applyRiskJson(M.readTree(json));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Invoked by the browser poll timer and on initial attach.
     */
    @ClientCallable
    private void _pollRisk() {
        try {
            JsonNode j = ApiClient.get(RISK_SUMMARY_API, JsonNode.class);
            applyRiskJson(j);
        } catch (Throwable ignored) {
        }
    }
}
