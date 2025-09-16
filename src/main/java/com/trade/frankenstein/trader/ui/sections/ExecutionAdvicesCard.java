package com.trade.frankenstein.trader.ui.sections;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.model.documents.Advice;
import com.trade.frankenstein.trader.ui.bridge.ApiClient;
import com.trade.frankenstein.trader.ui.shared.CardSection;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.LitRenderer;
import com.vaadin.flow.theme.lumo.LumoUtility;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.trade.frankenstein.trader.ui.bridge.ApiClient.post;

public class ExecutionAdvicesCard extends CardSection {

    private static final ObjectMapper M = ApiClient.json();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(IST);

    private final List<Advice> rows = new ArrayList<>();
    private final ListDataProvider<Advice> dataProvider = new ListDataProvider<>(rows);

    private final Input instrumentFilter = new Input();
    private final Input statusFilter = new Input();
    private final Input sideFilter = new Input();
    private final Input minConfFilter = new Input();
    private final Grid<Advice> grid;

    // Generator status chip
    private final Span genChip = new Span("Generator: —");
    private Instant lastNewAdvice = null;

    public ExecutionAdvicesCard() {
        super("Advice Queue");

        // Premium card styling with glass morphism effect
        getStyle()
                .setPadding("20px")
                .set("border-radius", "16px")
                .set("box-shadow", "0 10px 30px rgba(0, 0, 0, 0.08), 0 6px 12px rgba(0, 0, 0, 0.04)")
                .set("background", "linear-gradient(145deg, var(--lumo-base-color), rgba(255, 255, 255, 0.9))")
                .set("backdrop-filter", "blur(10px)")
                .set("border", "1px solid rgba(255, 255, 255, 0.2)")
                .set("transition", "all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1)");

        // Enhanced header with gradient accent
        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.getStyle()
                .set("border-bottom", "1px solid rgba(0, 0, 0, 0.05)")
                .set("padding-bottom", "12px")
                .set("margin-bottom", "16px");

        // Animated icon with gradient
        Icon adviceIcon = VaadinIcon.LIGHTBULB.create();
        adviceIcon.getStyle()
                .set("color", "var(--lumo-primary-color)")
                .set("background", "linear-gradient(135deg, var(--lumo-primary-color) 0%, var(--lumo-primary-color-50pct) 100%)")
                .set("background-clip", "text")
                .set("-webkit-background-clip", "text")
                .set("-webkit-text-fill-color", "transparent")
                .set("font-size", "1.5rem")
                .set("animation", "pulse 2s infinite")
                .set("padding", "4px");

        H4 title = new H4("Execution Advice");
        title.getStyle()
                .set("margin", "0")
                .set("font-weight", "600")
                .set("font-size", "1.25rem")
                .set("color", "var(--lumo-primary-text-color)")
                .set("letter-spacing", "0.5px");

        // Enhanced generator chip with animation
        genChip.getElement().getThemeList().add("badge");
        genChip.getStyle()
                .set("margin-left", "auto")
                .set("font-weight", "600")
                .set("border-radius", "999px")
                .set("padding", "6px 12px")
                .set("transition", "all 0.3s ease")
                .set("box-shadow", "0 2px 4px rgba(0, 0, 0, 0.1)")
                .set("text-transform", "uppercase")
                .set("font-size", "12px")
                .set("letter-spacing", "0.5px");

        // Refined refresh button
        Button refreshBtn = new Button("Refresh", e -> safeRefreshList());
        refreshBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        refreshBtn.setIcon(VaadinIcon.REFRESH.create());
        refreshBtn.getStyle()
                .set("margin-right", "8px")
                .set("font-weight", "500")
                .set("transition", "transform 0.2s ease")
                .set("border-radius", "8px");
        refreshBtn.getElement().executeJs("this.addEventListener('mouseover', function() { this.style.transform = 'scale(1.05)'; })");
        refreshBtn.getElement().executeJs("this.addEventListener('mouseout', function() { this.style.transform = 'scale(1)'; })");

        header.add(adviceIcon, title, refreshBtn, genChip);
        header.expand(title);

        // Enhanced filters section with floating effect
        Button clear = new Button("Clear", e -> clearFilters());
        clear.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        clear.getElement().getThemeList().add("tertiary-inline");
        clear.setIcon(VaadinIcon.ERASER.create());
        clear.getStyle()
                .set("transition", "opacity 0.2s ease")
                .set("border-radius", "8px")
                .set("font-weight", "500");

        enhanceFilterInput(sideFilter, "Side (BUY/SELL)");
        enhanceFilterInput(statusFilter, "Status");
        enhanceFilterInput(instrumentFilter, "Symbol");
        enhanceFilterInput(minConfFilter, "Min Conf");

        Span filterLabel = new Span("Filters:");
        filterLabel.getStyle()
                .set("font-weight", "500")
                .set("color", "var(--lumo-primary-color)")
                .set("letter-spacing", "0.5px");

        HorizontalLayout filters = new HorizontalLayout(filterLabel, sideFilter, statusFilter, instrumentFilter, minConfFilter, clear);
        filters.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        filters.getStyle()
                .set("gap", "12px")
                .set("padding", "16px")
                .set("background", "linear-gradient(145deg, var(--lumo-base-color), var(--lumo-contrast-5pct))")
                .set("border-radius", "12px")
                .set("margin-bottom", "20px")
                .set("box-shadow", "0 4px 12px rgba(0, 0, 0, 0.03)")
                .set("border", "1px solid rgba(0, 0, 0, 0.03)");

        // Premium grid styling
        grid = new Grid<>(Advice.class, false);
        grid.addThemeVariants(GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_ROW_STRIPES);
        grid.setAllRowsVisible(true);
        grid.setSelectionMode(Grid.SelectionMode.NONE);
        grid.setDataProvider(dataProvider);
        grid.getStyle()
                .set("border-radius", "12px")
                .set("overflow", "hidden")
                .set("box-shadow", "0 4px 16px rgba(0, 0, 0, 0.06)")
                .set("--lumo-grid-cell-border-width", "0")
                .set("--lumo-grid-cell-padding", "12px 16px");
        grid.getElement().executeJs("this.style.setProperty('--_lumo-grid-border-width', '0')");

        // Add custom CSS class for grid row styling
        grid.addClassName("premium-grid");
        getUI().ifPresent(ui -> ui.getPage().executeJs("""
                    const style = document.createElement('style');
                    style.textContent = `
                        .premium-grid tr:hover td {
                            background: rgba(0, 0, 0, 0.02) !important;
                            transition: background 0.2s ease;
                        }
                        .premium-grid [part~="header-cell"] {
                            background: linear-gradient(180deg, var(--lumo-base-color) 0%, var(--lumo-contrast-5pct) 100%);
                            font-weight: 600;
                        }
                        .premium-grid [part~="row"]:nth-child(even) {
                            background: rgba(0, 0, 0, 0.015);
                        }
                        .premium-grid [part~="cell"] {
                            transition: all 0.2s ease;
                        }
                        .order-badge {
                            background-color: var(--lumo-success-color-10pct);
                            color: var(--lumo-success-text-color);
                            font-weight: 500;
                            border-color: var(--lumo-success-color-20pct) !important;
                        }
                        .status-pill {
                            display: inline-flex;
                            align-items: center;
                            justify-content: center;
                            font-weight: 500;
                            letter-spacing: 0.3px;
                            box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
                        }
                        @keyframes pulse {
                            0% { transform: scale(1); }
                            50% { transform: scale(1.05); }
                            100% { transform: scale(1); }
                        }
                    `;
                    document.head.appendChild(style);
                """));

        Grid.Column<Advice> timeCol = grid.addColumn(this::formatTime).setHeader(createStyledHeader("Time")).setKey("time").setAutoWidth(true);
        grid.addColumn(Advice::getSymbol).setHeader(createStyledHeader("Symbol")).setAutoWidth(true);
        grid.addColumn(a -> opt(a.getTransaction_type())).setHeader(createStyledHeader("Side")).setAutoWidth(true);

        // Enhanced confidence column with improved visualizations
        grid.addColumn(LitRenderer.<Advice>of(
                                "<div style='display:flex;gap:10px;white-space:nowrap;align-items:center'>" +
                                        "<div style='display:flex;align-items:center'>" +
                                        "<span style='font-weight:600;color:var(--lumo-primary-color);letter-spacing:0.2px;'>${item.conf}%</span>" +
                                        "<span style='width:8px;height:8px;border-radius:50%;background:var(--lumo-primary-color);margin-left:6px;opacity:${item.confOp};box-shadow:0 0 4px rgba(var(--lumo-primary-color-rgb), 0.4)'></span>" +
                                        "</div>" +
                                        "<div style='display:flex;align-items:center'>" +
                                        "<span style='color:var(--lumo-secondary-text-color);font-weight:500;'>${item.tech}%</span>" +
                                        "<span style='width:7px;height:7px;border-radius:50%;background:var(--lumo-contrast);margin-left:4px;opacity:${item.techOp}'></span>" +
                                        "</div>" +
                                        "<div style='display:flex;align-items:center'>" +
                                        "<span style='color:var(--lumo-secondary-text-color);font-weight:500;'>${item.news}%</span>" +
                                        "<span style='width:7px;height:7px;border-radius:50%;background:var(--lumo-contrast);margin-left:4px;opacity:${item.newsOp}'></span>" +
                                        "</div>" +
                                        "</div>")
                        .withProperty("conf", a -> nz(a.getConfidence()))
                        .withProperty("tech", a -> nz(a.getTech()))
                        .withProperty("news", a -> nz(a.getNews()))
                        .withProperty("confOp", a -> opacityByConfidence(a.getConfidence()))
                        .withProperty("techOp", a -> opacityByConfidence(a.getTech()))
                        .withProperty("newsOp", a -> opacityByConfidence(a.getNews())))
                .setHeader(createStyledHeader("Confidence")).setAutoWidth(true);

        // Enhanced status pill with improved design
        grid.addColumn(LitRenderer.<Advice>of(
                                "<div style='display:flex;align-items:center'>" +
                                        "<span class='status-pill' style='background:${item.statusColor};color:white;padding:5px 10px;border-radius:12px;font-size:12px;'>" +
                                        "${item.status}</span></div>")
                        .withProperty("status", a -> a.getStatus() == null ? "PENDING" : a.getStatus().name())
                        .withProperty("statusColor", a -> getStatusColor(a.getStatus())))
                .setHeader(createStyledHeader("Status")).setAutoWidth(true);

        // Enhanced reason column with subtle styling
        grid.addColumn(new ComponentRenderer<>(a -> {
            Span reason = new Span(opt(a.getReason()));
            reason.getStyle()
                    .set("white-space", "nowrap")
                    .set("overflow", "hidden")
                    .set("text-overflow", "ellipsis")
                    .set("display", "inline-block")
                    .set("max-width", "32ch")
                    .set("font-style", "italic")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("font-size", "13px")
                    .set("border-left", "2px solid var(--lumo-contrast-10pct)")
                    .set("padding-left", "8px");
            return reason;
        })).setHeader(createStyledHeader("Reason")).setFlexGrow(1);

        // Enhanced order badge
        grid.addColumn(LitRenderer.<Advice>of(
                                "<div style='display:flex;align-items:center'>" +
                                        "<span class='${item.hasOrder}' style='padding:4px 10px;border-radius:12px;font-size:13px;border:1px solid var(--lumo-contrast-20pct);transition:all 0.2s ease;'>" +
                                        "${item.orderId}</span></div>")
                        .withProperty("orderId", a -> opt(a.getOrder_id()))
                        .withProperty("hasOrder", a -> a.getOrder_id() != null && !a.getOrder_id().isEmpty() ? "order-badge" : "no-order-badge"))
                .setHeader(createStyledHeader("Order"))
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(a -> {
            // Premium styled execute button
            Button exec = new Button("Execute", e -> {
                try {
                    post("/api/advice/" + a.getId() + "/execute", null, Void.class);
                    toastSuccess("Execute requested");
                } catch (Throwable ex) {
                    toastError("Execute failed");
                }
            });
            exec.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SUCCESS, ButtonVariant.LUMO_SMALL);
            exec.setIcon(VaadinIcon.PLAY.create());
            exec.getStyle()
                    .set("border-radius", "8px")
                    .set("box-shadow", "0 2px 5px rgba(0, 154, 100, 0.2)")
                    .set("padding", "6px 12px")
                    .set("text-transform", "uppercase")
                    .set("font-size", "12px")
                    .set("font-weight", "600")
                    .set("letter-spacing", "0.4px")
                    .set("transition", "all 0.2s cubic-bezier(0.4, 0, 0.2, 1)");
            exec.getElement().executeJs("this.addEventListener('mouseover', function() { this.style.transform = 'translateY(-2px)'; this.style.boxShadow = '0 4px 8px rgba(0, 154, 100, 0.3)'; })");
            exec.getElement().executeJs("this.addEventListener('mouseout', function() { this.style.transform = 'translateY(0)'; this.style.boxShadow = '0 2px 5px rgba(0, 154, 100, 0.2)'; })");

            // Premium styled dismiss button
            Button dismiss = new Button("Dismiss", e -> {
                try {
                    post("/api/advice/" + a.getId() + "/dismiss", null, Void.class);
                    toastSuccess("Dismissed");
                } catch (Throwable ex) {
                    toastError("Dismiss failed");
                }
            });
            dismiss.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_SMALL);
            dismiss.setIcon(VaadinIcon.CLOSE_SMALL.create());
            dismiss.getStyle()
                    .set("border-radius", "8px")
                    .set("padding", "6px 12px")
                    .set("font-size", "12px")
                    .set("font-weight", "500")
                    .set("transition", "all 0.2s ease");
            dismiss.getElement().executeJs("this.addEventListener('mouseover', function() { this.style.backgroundColor = 'rgba(255, 82, 82, 0.08)'; })");
            dismiss.getElement().executeJs("this.addEventListener('mouseout', function() { this.style.backgroundColor = ''; })");

            // Premium styled why button
            Button why = new Button("Why?", e -> {
                try {
                    Advice full = ApiClient.get("/api/advice/" + a.getId(), Advice.class);
                    String expl = (full != null && notBlank(full.getReason())) ? full.getReason() : opt(a.getReason());
                    showWhyDialog(a, expl);
                } catch (Throwable ex) {
                    showWhyDialog(a, opt(a.getReason()));
                }
            });
            why.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_CONTRAST, ButtonVariant.LUMO_SMALL);
            why.setIcon(VaadinIcon.INFO_CIRCLE.create());
            why.getStyle()
                    .set("border-radius", "8px")
                    .set("border", "1px solid var(--lumo-contrast-20pct)")
                    .set("padding", "6px 12px")
                    .set("font-size", "12px")
                    .set("font-weight", "500")
                    .set("transition", "all 0.2s ease");
            why.getElement().executeJs("this.addEventListener('mouseover', function() { this.style.backgroundColor = 'var(--lumo-contrast-5pct)'; })");
            why.getElement().executeJs("this.addEventListener('mouseout', function() { this.style.backgroundColor = ''; })");

            HorizontalLayout row = new HorizontalLayout(exec, dismiss, why);
            row.getStyle().set("gap", "8px");
            return row;
        })).setHeader(createStyledHeader("Actions")).setAutoWidth(true);

        grid.sort(Collections.singletonList(new GridSortOrder<>(timeCol, SortDirection.DESCENDING)));

        VerticalLayout mainContent = new VerticalLayout(header, filters, grid);
        mainContent.setPadding(false);
        mainContent.setSpacing(false);
        mainContent.getStyle().set("gap", "12px");
        add(mainContent);

        // Initial load
        safeRefreshList();

        // Ensure a shared SSE connection exists and dispatch only the topics we need
        getUI().ifPresent(ui -> ui.getPage().executeJs("""
                    if(!window.tfSSE){
                      try{
                        window.tfSSE = new EventSource('/api/stream');
                      }catch(e){ /* ignore */ }
                    }
                    if(window.tfSSE && !window.tfSSE._advBound){
                      window.tfSSE.addEventListener('advice.new',     e => window.dispatchEvent(new CustomEvent('advice.new',     { detail: e.data })));
                      window.tfSSE.addEventListener('advice.updated', e => window.dispatchEvent(new CustomEvent('advice.updated', { detail: e.data })));
                      window.tfSSE._advBound = true;
                    }
                    const cmp = $0;
                    window.addEventListener('advice.new',     e=>cmp.$server._onAdviceNew(e.detail));
                    window.addEventListener('advice.updated', e=>cmp.$server._onAdviceUpd(e.detail));
                    // Poll fallback every 15s to stay in sync even if SSE drops
                    if(!window._advRefresh){
                      window._advRefresh = setInterval(()=>{ try{ cmp.$server._refreshList(); }catch(e){} }, 15000);
                    }
                """, getElement()));
    }

    // ---- UI helpers ------------------------------------------------------

    private Div createStyledHeader(String text) {
        Div header = new Div(new Span(text));
        header.getStyle().set("font-weight", "600").set("color", "var(--lumo-secondary-text-color)").set("font-size", "14px");
        return header;
    }

    private void enhanceFilterInput(Input input, String placeholder) {
        input.setPlaceholder(placeholder);
        input.getElement().setAttribute("aria-label", placeholder);
        input.getStyle().set("border-radius", "6px").set("--lumo-text-field-size", "var(--lumo-size-s)").set("min-width", "120px");
    }

    private String opacityByConfidence(Integer value) {
        if (value == null) return "0.2";
        if (value >= 80) return "1.0";
        if (value >= 60) return "0.8";
        if (value >= 40) return "0.6";
        if (value >= 20) return "0.4";
        return "0.2";
    }

    private String getStatusColor(Enum<?> status) {
        if (status == null) return "var(--lumo-contrast)";
        switch (status.name()) {
            case "EXECUTED":
                return "var(--lumo-success-color)";
            case "DISMISSED":
                return "var(--lumo-error-color)";
            case "PENDING":
                return "var(--lumo-primary-color)";
            default:
                return "var(--lumo-contrast)";
        }
    }

    private void showLoadingError() {
        Notification notification = new Notification("Could not load advice data. Will retry automatically.");
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR);
        notification.setPosition(Notification.Position.TOP_CENTER);
        notification.setDuration(3000);
        notification.open();
    }

    private void setData(List<Advice> adv) {
        rows.clear();
        adv.sort(Comparator.comparing(Advice::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        rows.addAll(adv);
        dataProvider.refreshAll();

        // update generator heartbeat based on newest advice timestamp
        if (!adv.isEmpty() && adv.get(0).getCreatedAt() != null) {
            lastNewAdvice = adv.get(0).getCreatedAt();
        }
        updateGeneratorChip();
    }

    private void prepend(Advice a) {
        rows.add(0, a);
        dataProvider.refreshAll();

        if (a != null && a.getCreatedAt() != null) {
            lastNewAdvice = a.getCreatedAt();
            updateGeneratorChip();
        }
    }

    private void clearFilters() {
        sideFilter.clear();
        statusFilter.clear();
        instrumentFilter.clear();
        minConfFilter.clear();
    }

    private void toastSuccess(String msg) {
        Notification n = Notification.show(msg, 1500, Notification.Position.TOP_END);
        n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    private void toastError(String msg) {
        Notification n = Notification.show(msg, 2000, Notification.Position.TOP_END);
        n.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    private void showWhyDialog(Advice a, String reasonOverride) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Why this advice?");
        d.getElement().getThemeList().add("advice-dialog");
        d.getElement().getStyle().set("border-radius", "12px").set("box-shadow", "0 8px 24px rgba(0,0,0,0.1)");

        Div headerIcon = new Div(VaadinIcon.INFO_CIRCLE.create());
        headerIcon.getStyle().set("color", "var(--lumo-primary-color)").set("margin-right", "8px");
        d.getHeader().add(headerIcon);

        Div content = new Div();
        content.addClassName(LumoUtility.Padding.MEDIUM);
        content.setWidthFull();

        content.add(createInfoRow("Symbol", opt(a.getSymbol())));
        content.add(createInfoRow("Side", opt(a.getTransaction_type())));
        String confidenceText = nz(a.getConfidence()) + "% (Tech " + nz(a.getTech()) + "% / News " + nz(a.getNews()) + "%)";
        content.add(createInfoRow("Confidence", confidenceText));
        String qty = (a.getQuantity() == 0 ? "—" : String.valueOf(a.getQuantity()));
        content.add(createInfoRow("Quantity", qty));
        String reason = (notBlank(reasonOverride) ? reasonOverride : opt(a.getReason()));
        content.add(createInfoRow("Reason", reason));
        d.add(content);

        Button closeButton = new Button("Close", e -> d.close());
        closeButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        closeButton.getStyle().set("margin-top", "16px");
        closeButton.setIcon(VaadinIcon.CHECK.create());

        HorizontalLayout footer = new HorizontalLayout(closeButton);
        footer.setWidthFull();
        footer.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

        d.getFooter().add(footer);
        d.setModal(true);
        d.setDraggable(true);
        d.setResizable(true);
        d.open();
    }

    private HorizontalLayout createInfoRow(String label, String value) {
        Span labelSpan = new Span(label + ":");
        labelSpan.getStyle().set("font-weight", "600").set("color", "var(--lumo-secondary-text-color)").set("width", "100px");

        Span valueSpan = new Span(value);
        valueSpan.getStyle().set("color", "var(--lumo-primary-text-color)");

        HorizontalLayout row = new HorizontalLayout(labelSpan, valueSpan);
        row.setWidthFull();
        row.setPadding(false);
        row.setMargin(false);
        row.getStyle().set("padding", "6px 0");
        return row;
    }

    private void updateGeneratorChip() {
        boolean active = false;
        if (lastNewAdvice != null) {
            active = Duration.between(lastNewAdvice, Instant.now()).abs().getSeconds() <= 120;
        }
        genChip.setText(active ? "Generator: ACTIVE" : "Generator: IDLE");
        genChip.getElement().getThemeList().remove(active ? "error" : "success");
        genChip.getElement().getThemeList().add(active ? "success" : "error");
    }

    private void safeRefreshList() {
        try {
            Advice[] arr = ApiClient.get("/api/advice", Advice[].class);
            if (arr != null) setData(Arrays.asList(arr));
        } catch (Throwable ignored) {
            showLoadingError();
        }
    }

    // ---- SSE server hooks & poll fallback --------------------------------

    @ClientCallable
    private void _refreshList() {
        safeRefreshList();
    }

    @ClientCallable
    private void _onAdviceNew(String json) {
        try {
            Advice a = M.readValue(json, Advice.class);
            if (a != null) {
                prepend(a);
                toastSuccess("New advice received");
            }
        } catch (Throwable ignored) {
        }
    }

    @ClientCallable
    private void _onAdviceUpd(String json) {
        try {
            Advice a = M.readValue(json, Advice.class);
            if (a == null || a.getId() == null) return;
            for (int i = 0; i < rows.size(); i++) {
                if (a.getId().equals(rows.get(i).getId())) {
                    rows.set(i, a);
                    dataProvider.refreshAll();
                    if (a.getCreatedAt() != null) {
                        lastNewAdvice = a.getCreatedAt();
                        updateGeneratorChip();
                    }
                    return;
                }
            }
            prepend(a);
        } catch (Throwable ignored) {
        }
    }

    // ---- small utils ------------------------------------------------------

    private String formatTime(Advice a) {
        Instant ts = a.getCreatedAt();
        return ts == null ? "—" : HHMMSS.format(ts);
    }

    private static String opt(String s) {
        return (s == null || s.trim().isEmpty()) ? "—" : s;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
