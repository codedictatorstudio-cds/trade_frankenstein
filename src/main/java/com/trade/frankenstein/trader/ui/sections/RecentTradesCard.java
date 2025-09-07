package com.trade.frankenstein.trader.ui.sections;

import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.model.Trade;
import com.trade.frankenstein.trader.ui.shared.CardSection;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.LitRenderer;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.trade.frankenstein.trader.ui.shared.GridSpan.forceSingleLineFit;

public class RecentTradesCard extends CardSection {

    public RecentTradesCard() {
        super("Recent Trades");

        // ===== Data (one source of truth) =====
        List<Trade> items = new ArrayList<>(sampleData());
        ListDataProvider<Trade> dataProvider = new ListDataProvider<>(items);

        // ===== Grid =====
        Grid<Trade> grid = new Grid<>(Trade.class, false);
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setAllRowsVisible(true);
        grid.setSelectionMode(Grid.SelectionMode.NONE);

        grid.addColumn(Trade::getSymbol)
                .setHeader("Symbol")
                .setAutoWidth(true);

        grid.addColumn(LitRenderer.<Trade>of(
                                "<span style=\"padding:4px 8px;border-radius:999px;font-size:12px;font-weight:700;" +
                                        "border:1px solid ${item.side==='BUY' ? '#bbf7d0' : '#fecaca'};" +
                                        "background:${item.side==='BUY' ? '#ecfdf5' : '#fff1f2'};" +
                                        "color:${item.side==='BUY' ? '#166534' : '#991b1b'};\">" +
                                        "${item.side}</span>")
                        .withProperty("side", t -> t.getSide().name()))
                .setHeader("Side")
                .setAutoWidth(true);

        grid.addColumn(t -> formatINR(t.getEntry().doubleValue()))
                .setHeader("Entry")
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(t -> formatINR(t.getCurrent().doubleValue()))
                .setHeader("Current")
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(Trade::getQty)
                .setHeader("Qty")
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(t -> {
                    String color = t.getPnl().compareTo(BigDecimal.ZERO) >= 0 ? "#16a34a" : "#dc2626";
                    Paragraph p = new Paragraph((t.getPnl().compareTo(BigDecimal.ZERO) >= 0 ? "₹ +" : "₹ ") + t.getPnl().abs());
                    p.getStyle().set("margin", "0").set("font-weight", "700").set("color", color);
                    return p;
                }))

                .setHeader("PnL")
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(Trade::getDuration)
                .setHeader("Duration")
                .setAutoWidth(true);

        grid.addColumn(Trade::getOrderId)
                .setHeader("OrderId")
                .setAutoWidth(true)
                .setClassNameGenerator(r -> "mono");

        grid.addColumn(new ComponentRenderer<>(t -> {
            Button why = new Button("Why?");
            why.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            why.addClickListener(e -> openWhy(t));
            forceSingleLineFit(why);
            return why;
        })).setHeader("").setAutoWidth(true);

        grid.setDataProvider(dataProvider);
        add(grid);
    }

    private void openWhy(Trade t) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Why this trade?");
        d.add(new Paragraph("Symbol: " + t.getSymbol()));
        d.add(new Paragraph("Side: " + t.getSide()));
        d.add(new Paragraph("Entry: ₹ " + t.getEntry()));
        d.add(new Paragraph("Current: ₹ " + t.getCurrent()));
        d.add(new Paragraph("PnL: ₹ " + t.getPnl()));
        d.add(new Paragraph("Duration: " + t.getDuration()));
        d.open();
    }

    private List<Trade> sampleData() {
        return List.of(
                new Trade("NIFTY24SEP 22500CE", OrderSide.BUY, 74.25, 79.10, 50, 242.50, "12m", "#ORD-812A"),
                new Trade("NIFTY24SEP 22400PE", OrderSide.SELL, 86.40, 88.00, 50, -80.00, "7m", "#ORD-663F")
        );
    }

    private String formatINR(double amount) {
        Locale enIN = new Locale("en", "IN");
        NumberFormat nf = NumberFormat.getCurrencyInstance(enIN);
        nf.setMaximumFractionDigits(0);
        nf.setMinimumFractionDigits(0);
        return nf.format(amount);
    }
}
