package com.trade.frankenstein.trader.ui.sections;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.frankenstein.trader.enums.OrderSide;
import com.trade.frankenstein.trader.enums.TradeStatus;
import com.trade.frankenstein.trader.ui.bridge.ApiClient;
import com.trade.frankenstein.trader.ui.shared.CardSection;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class RecentTradesCard extends CardSection {

    private static final ObjectMapper M = com.trade.frankenstein.trader.ui.bridge.ApiClient.json();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(IST);

    private final Grid<TradeRowDto> grid = new Grid<>(TradeRowDto.class, false);
    private final List<TradeRowDto> items = new ArrayList<>();
    private final ListDataProvider<TradeRowDto> dataProvider = new ListDataProvider<>(items);

    public RecentTradesCard() {
        super("Recent Trades");
        getStyle().set("grid-column", "var(--trades-span, span 8)");

        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setSelectionMode(Grid.SelectionMode.NONE);
        grid.setDataProvider(dataProvider);

        // Columns mapped to TradeRowDto fields
        grid.addColumn(t -> formatTime(t.time))
                .setHeader("Time")
                .setAutoWidth(true);

        grid.addColumn(t -> nullSafe(t.instrument))
                .setHeader("Instrument")
                .setAutoWidth(true);

        grid.addColumn(t -> t.side == null ? "—" : t.side.name())
                .setHeader("Side")
                .setAutoWidth(true);

        grid.addColumn(TradeRowDto::quantity)
                .setHeader("Qty")
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(t -> formatINR(t.entryPrice))
                .setHeader("Entry")
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(t -> formatINR(t.exitPrice))
                .setHeader("Exit")
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(t -> t.status == null ? "—" : t.status.name())
                .setHeader("Status")
                .setAutoWidth(true);

        grid.addColumn(LitRenderer.<TradeRowDto>of(
                                "<span style='font-weight:700;color:${color}'>₹ ${pnl}</span>")
                        .withProperty("color", t -> ge0(t.realizedPnl) ? "#16a34a" : "#dc2626")
                        .withProperty("pnl", t -> signedAbs(t.realizedPnl)))
                .setHeader("Realized PnL")
                .setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true);

        grid.addColumn(t -> firstNonBlank(t.brokerTradeId, t.publicId, "—"))
                .setHeader("Order")
                .setAutoWidth(true)
                .setClassNameGenerator(r -> "mono");

        grid.addColumn(new ComponentRenderer<>(t -> {
                    Button why = new Button("Why?");
                    why.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
                    why.addClickListener(e -> openWhy(t));
                    return why;
                })).setHeader("")
                .setAutoWidth(true);

        add(grid);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        // Initial fetch (controller present in your uploads)
        try {
            TradeRowDto[] arr = ApiClient.get("/api/trades/recent?limit=20", TradeRowDto[].class);
            if (arr != null) {
                items.clear();
                items.addAll(Arrays.asList(arr));
                dataProvider.refreshAll();
            }
        } catch (Throwable ignored) {
        }

        // SSE hooks
        getUI().ifPresent(ui -> ui.getPage().executeJs("""
                  const cmp=$0;
                  window.addEventListener('trade.created', e=>cmp.$server._onTradeNew(e.detail));
                  window.addEventListener('trade.updated', e=>cmp.$server._onTradeUpd(e.detail));
                """, getElement()));
    }

    private void openWhy(TradeRowDto t) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Why this trade?");
        d.add(new Paragraph("Instrument: " + nullSafe(t.instrument)));
        d.add(new Paragraph("Side: " + (t.side == null ? "—" : t.side.name())));
        d.add(new Paragraph("Qty: " + t.quantity));
        d.add(new Paragraph("Entry: " + formatINR(t.entryPrice)));
        d.add(new Paragraph("Exit: " + formatINR(t.exitPrice)));
        d.add(new Paragraph("Status: " + (t.status == null ? "—" : t.status.name())));
        d.add(new Paragraph("Realized PnL: " + formatINR(t.realizedPnl)));
        d.add(new Paragraph("Order: " + firstNonBlank(t.brokerTradeId, t.publicId, "—")));
        d.add(new Paragraph("Time: " + formatTime(t.time)));
        d.open();
    }

    // ---- SSE server hooks ----
    @ClientCallable
    private void _onTradeNew(String json) {
        try {
            TradeRowDto t = M.readValue(json, TradeRowDto.class);
            if (t != null) {
                items.add(0, t);
                dataProvider.refreshAll();
            }
        } catch (Throwable ignored) {
        }
    }

    @ClientCallable
    private void _onTradeUpd(String json) {
        try {
            TradeRowDto t = M.readValue(json, TradeRowDto.class);
            if (t == null) return;
            int idx = indexOf(t);
            if (idx >= 0) {
                items.set(idx, t);
            } else {
                items.add(0, t);
            }
            dataProvider.refreshAll();
        } catch (Throwable ignored) {
        }
    }

    // ---- helpers ----
    private int indexOf(TradeRowDto t) {
        // Prefer id, then publicId, then brokerTradeId
        for (int i = 0; i < items.size(); i++) {
            TradeRowDto r = items.get(i);
            if (eq(t.id, r.id)) return i;
            if (nz(t.publicId) && t.publicId.equals(r.publicId)) return i;
            if (nz(t.brokerTradeId) && t.brokerTradeId.equals(r.brokerTradeId)) return i;
        }
        return -1;
    }

    private static boolean eq(Long a, Long b) {
        return Objects.equals(a, b);
    }

    private static boolean nz(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "—" : s;
    }

    private static String firstNonBlank(String a, String b, String def) {
        if (nz(a)) return a;
        if (nz(b)) return b;
        return def;
    }

    private static String formatTime(Instant ts) {
        if (ts == null) return "—";
        return TIME_FMT.format(ts);
    }

    private static boolean ge0(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) >= 0;
    }

    private static String signedAbs(BigDecimal v) {
        if (v == null) return "0";
        String sign = v.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "-";
        return sign + v.abs();
    }

    private static String formatINR(BigDecimal amount) {
        if (amount == null) return "—";
        Locale enIN = new Locale("en", "IN");
        NumberFormat nf = NumberFormat.getCurrencyInstance(enIN);
        nf.setMaximumFractionDigits(0);
        nf.setMinimumFractionDigits(0);
        return nf.format(amount);
    }

    /**
     * Local mirror of backend TradeRowDto record to ensure UI compiles even if the shared model
     * artifact isn’t on the UI classpath. Jackson maps by field names.
     */
    public static class TradeRowDto {
        public Long id;
        public String publicId;
        public String brokerTradeId;
        public Instant time;
        public String instrument;
        public OrderSide side;
        public int quantity;
        public BigDecimal entryPrice;
        public BigDecimal exitPrice;
        public TradeStatus status;
        public BigDecimal realizedPnl;

        public Long id() {
            return id;
        }

        public String publicId() {
            return publicId;
        }

        public String brokerTradeId() {
            return brokerTradeId;
        }

        public Instant time() {
            return time;
        }

        public String instrument() {
            return instrument;
        }

        public OrderSide side() {
            return side;
        }

        public int quantity() {
            return quantity;
        }

        public BigDecimal entryPrice() {
            return entryPrice;
        }

        public BigDecimal exitPrice() {
            return exitPrice;
        }

        public TradeStatus status() {
            return status;
        }

        public BigDecimal realizedPnl() {
            return realizedPnl;
        }
    }
}
