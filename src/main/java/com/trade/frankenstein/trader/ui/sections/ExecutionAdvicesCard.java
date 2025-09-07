package com.trade.frankenstein.trader.ui.sections;

import com.trade.frankenstein.trader.model.Advice;
import com.trade.frankenstein.trader.ui.shared.CardSection;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.provider.SortDirection;   // <-- add this import
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.LitRenderer;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ExecutionAdvicesCard extends CardSection {

    private final Grid<Advice> grid = new Grid<>(Advice.class, false);
    private final List<Advice> rows = new ArrayList<>();
    private final ListDataProvider<Advice> dataProvider = new ListDataProvider<>(rows);

    private final Input instrumentFilter = new Input();
    private final Input statusFilter = new Input();
    private final Input sideFilter = new Input();
    private final Input minConfFilter = new Input();

    public ExecutionAdvicesCard() {
        super("Advice Queue");

        getStyle().setPadding("10px 12px 12px");

        Button clear = new Button("Clear", e -> clearFilters());
        clear.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        sideFilter.setPlaceholder("Side");
        statusFilter.setPlaceholder("Status");
        instrumentFilter.setPlaceholder("Instrument");
        minConfFilter.setPlaceholder("Min Conf");

        HorizontalLayout filters = new HorizontalLayout(
                new Span("Clear"), clear,
                sideFilter, statusFilter, instrumentFilter, minConfFilter
        );
        filters.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        filters.getStyle().set("gap", "8px").set("padding-bottom", "8px");

        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setAllRowsVisible(true);
        grid.setSelectionMode(Grid.SelectionMode.NONE);
        grid.setDataProvider(dataProvider);

        // Keep a reference + set a real key
        Grid.Column<Advice> timeCol = grid.addColumn(Advice::getTime)
                .setHeader("Time")
                .setKey("time")                    // <-- key, not header text
                .setAutoWidth(true);

        grid.addColumn(Advice::getInstrument).setHeader("Instrument").setAutoWidth(true);
        grid.addColumn(Advice::getSide).setHeader("Side").setAutoWidth(true);

        grid.addColumn(LitRenderer.<Advice>of(
                                "<div style='display:flex;gap:8px;white-space:nowrap'>" +
                                        "<span>${item.conf}%</span>" +
                                        "<span>${item.tech}%</span>" +
                                        "<span>${item.news}%</span>" +
                                        "</div>")
                        .withProperty("conf", Advice::getConfidence)
                        .withProperty("tech", Advice::getTech)
                        .withProperty("news", Advice::getNews))
                .setHeader("Conf  Tech  News").setAutoWidth(true);

        grid.addColumn(Advice::getStatus).setHeader("Status").setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(a -> {
            Span reason = new Span(a.getReason());
            reason.getStyle()
                    .set("white-space", "nowrap")
                    .set("overflow", "hidden")
                    .set("text-overflow", "ellipsis")
                    .set("display", "inline-block")
                    .set("max-width", "32ch");
            return reason;
        })).setHeader("Reason").setFlexGrow(1);

        grid.addColumn(a -> Optional.ofNullable(a.getOrderId()).orElse("—"))
                .setHeader("Order").setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(a -> {
            Button exec = new Button("Execute", e -> toast("Executing…"));
            exec.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SUCCESS);

            Button dismiss = new Button("Dismiss", e -> toast("Dismissed"));
            dismiss.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

            Button why = new Button("Why?", e -> showWhyDialog(a));
            why.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);

            HorizontalLayout row = new HorizontalLayout(exec, dismiss, why);
            row.getStyle().set("gap", "6px");
            return row;
        })).setHeader("Actions").setAutoWidth(true);

        // ✅ Default sort: newest first (DESC on time column)
        grid.sort(List.of(new GridSortOrder<>(timeCol, SortDirection.DESCENDING)));

        add(filters, grid);

        prepend(sample());
    }

    public void setData(List<Advice> adv) {
        rows.clear();
        rows.addAll(adv);
        dataProvider.refreshAll();
    }

    public void prepend(Advice a) {
        rows.add(0, a);
        dataProvider.refreshAll();
    }

    private void clearFilters() {
        sideFilter.clear();
        statusFilter.clear();
        instrumentFilter.clear();
        minConfFilter.clear();
    }

    private Advice sample() {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        Advice a = new Advice();
        a.setTime(time);
        a.setInstrument("NIFTY 24900 CE");
        a.setSide("BUY");
        a.setConfidence(87);
        a.setTech(75);
        a.setNews(90);
        a.setStatus("PENDING");
        a.setReason("Strong uptrend with positive news sentiment and technical indicators aligning.");
        a.setOrderId(null);
        return a;
    }

    private void toast(String msg) {
        Notification.show(msg, 1500, Notification.Position.TOP_CENTER);
    }

    private void showWhyDialog(Advice a) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Why this advice?");
        VerticalLayout body = new VerticalLayout(
                new Span("Instrument: " + a.getInstrument()),
                new Span("Side: " + a.getSide()),
                new Span("Confidence: " + a.getConfidence() + "%"),
                new Span("Tech/News: " + a.getTech() + "% / " + a.getNews() + "%"),
                new Span("Reason: " + a.getReason())
        );
        body.setPadding(false);
        body.setSpacing(false);
        body.getStyle().set("gap", "4px");
        d.add(body);
        d.setModal(true);
        d.setDraggable(true);
        d.setResizable(true);
        d.open();
    }
}
