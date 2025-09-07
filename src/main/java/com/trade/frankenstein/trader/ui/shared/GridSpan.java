// src/main/java/com/trade/frankenstein/trader/ui/shared/GridSpan.java
package com.trade.frankenstein.trader.ui.shared;

import com.vaadin.flow.component.HasStyle;

public final class GridSpan {
    private GridSpan() {}

    /** (Existing) span-4 sizing — keep if you still use grid elsewhere */
    public static void applySpan4(HasStyle c) {
        c.getStyle().set("grid-column", "span 4 / span 4");
        c.getStyle()
                .set("flex", "0 0 calc((100% - 2*var(--card-gap,12px)) / 3)")
                .set("max-width", "calc((100% - 2*var(--card-gap,12px)) / 3)")
                .set("min-width", "280px")
                .set("width", "100%");
    }

    /** Override for single-line rows: let each item shrink/grow on one line. */
    public static void forceSingleLineFit(HasStyle c) {
        c.getStyle()
                .set("flex", "1 1 0")   // share row evenly, shrink as needed
                .set("max-width", "unset")
                .set("min-width", "0")
                .set("width", "auto")
                .set("grid-column", "auto"); // neutralize span-4 in a row
    }
}
