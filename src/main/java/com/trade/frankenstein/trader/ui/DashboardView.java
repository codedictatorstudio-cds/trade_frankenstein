package com.trade.frankenstein.trader.ui;

import com.trade.frankenstein.trader.common.AuthCodeHolder;
import com.trade.frankenstein.trader.ui.bridge.EngineApiClient;
import com.trade.frankenstein.trader.ui.bridge.SseBridge;
import com.trade.frankenstein.trader.ui.header.AppHeader;
import com.trade.frankenstein.trader.ui.header.ControlsBar;
import com.trade.frankenstein.trader.ui.sections.*;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.*;

import java.util.Collections;

@PageTitle("TradeFrankenstein – Dashboard")
@Route("dashboard")
@CssImport("./styles/dashboard.css")
public class DashboardView extends VerticalLayout implements BeforeEnterObserver {

    public DashboardView() {

        Component SseBridge = new SseBridge();
        add(SseBridge);

        // Base layout
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        setMargin(false);
        addClassName("view-dashboard");

        // ===== 1/21: App Header (sticky via CSS) =====
        AppHeader header = new AppHeader(new EngineApiClient());
        add(header);

        // ===== 2/21: Controls bar row (full-bleed) =====
        HorizontalLayout controlsRow = new HorizontalLayout();
        controlsRow.addClassName("controls-row");
        controlsRow.setWidthFull();
        controlsRow.setPadding(false);
        controlsRow.setSpacing(false);
        controlsRow.setAlignItems(Alignment.CENTER);

        ControlsBar controls = new ControlsBar();
        controls.setWidthFull();
        controlsRow.add(controls);
        controlsRow.setFlexGrow(1, controls); // let it expand across the row
        add(controlsRow);

        // ===== Main container (centered) =====
        Div container = new Div();
        container.getStyle().set("width", "100%").set("max-width", "1400px").set("margin", "0 auto").set("padding", "12px"); // page-side padding

        // 🔒 Make AccountSummary + ProfitLoss the exact same block size baseline
        container.getStyle().set("--account-summary-span", "span 4")   // 12-col grid → width equivalent
                .set("--account-summary-min-h", "260px");   // tweak to match your AccountSummary height

        add(container);

        // ===== 12-column grid =====
        Div grid = new Div();
        grid.addClassName("tf-grid");
        container.add(grid);

        // ===== 19 content sections (total = 21 including header + controls) =====

        // Row A
        RegimeDecisionCard regimeCard = new RegimeDecisionCard();
        addToGrid(grid, regimeCard, 6);
        RiskPanelCard riskCard = new RiskPanelCard();
        addToGrid(grid, riskCard, 6);


        // Row C
        addToGrid(grid, new ExecutionAdvicesCard(), 12);

        // Row D
        addToGrid(grid, new RecentTradesCard(), 8);

        // Row E
        addToGrid(grid, new MarketSentimentCard(), 4);


    }

    private SseBridge sse;

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        // Single SSE bridge for the whole UI
        if (sse == null) {
            sse = new SseBridge().topics(
                    "engine.state",
                    "engine.heartbeat",
                    "decision.quality",
                    "risk.summary",
                    "risk.circuit",
                    "order.*",
                    "trade.*",
                    "sentiment.update",
                    "advice.new",
                    "advice.updated",
                    "trade.created",
                    "trade.updates"
            );
            add(sse); // invisible; lives in the UI
        }
    }


    /**
     * Wrap a component with a div that spans N columns (1..12).
     * Note: CSS breakpoints can override this to full-span on small screens.
     */
    private void addToGrid(Div grid, Component c, int spanCols) {
        Div cell = new Div(c);
        // clamp span between 1 and 12
        int span = Math.max(1, Math.min(12, spanCols));
        cell.getStyle().set("grid-column", "span " + span);
        cell.getStyle().set("min-height", "var(--account-summary-min-h, 260px)");
        cell.addClassName("tf-cell");
        grid.add(cell);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        Location location = event.getLocation();
        QueryParameters queryParameters = location.getQueryParameters();

        // Capture specific parameters (existing functionality)
        String code = queryParameters.getParameters().getOrDefault("code", Collections.singletonList("")).get(0);
        if (code != null && !code.isBlank()) {
            System.out.println("OAuth code received: " + code);
            AuthCodeHolder holder = AuthCodeHolder.getInstance();
            holder.set(code);
        }
    }

}
