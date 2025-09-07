package com.trade.frankenstein.trader.ui;

import com.trade.frankenstein.trader.ui.header.AppHeader;
import com.trade.frankenstein.trader.ui.header.ControlsBar;
import com.trade.frankenstein.trader.ui.sections.*;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@PageTitle("TradeFrankenstein – Dashboard")
@Route("dashboard")
@CssImport("./styles/dashboard.css")
public class DashboardView extends VerticalLayout {

    public DashboardView() {
        // Base layout
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        setMargin(false);
        addClassName("view-dashboard");

        // ===== 1/21: App Header (sticky via CSS) =====
        AppHeader header = new AppHeader();
        header.setWidthFull();
        header.setSizeFull();
        header.setHeightFull();
        header.addClassName("tf-header");
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
        addToGrid(grid, new RegimeDecisionCard(), 6);
        RiskPanelCard riskCard = new RiskPanelCard();
        riskCard.setBudgetLeft(8420);  // ₹ 8,420
        riskCard.setLots(4, 6);         // "4 / 6"
        riskCard.setDailyLossPercent(37.5);         // 37%
        riskCard.setOrdersPerMinutePercent(22);     // 22%

        addToGrid(grid, riskCard, 6);


        // Row C
        addToGrid(grid, new ExecutionAdvicesCard(), 12);

        // Row D
        addToGrid(grid, new RecentTradesCard(), 12);

        // Row E
        addToGrid(grid, new MarketSentimentCard(), 4);


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

}
