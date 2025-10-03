package com.trade.frankenstein.trader.ui.bridge;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.UI;

public final class GlobalModeChangedEvent extends ComponentEvent<UI> {
    private final String mode; // "sandbox" or "live"

    public GlobalModeChangedEvent(UI source, boolean fromClient, String mode) {
        super(source, fromClient);
        this.mode = mode == null ? "sandbox" : mode.toLowerCase();
    }

    public String getMode() {
        return mode;
    }
}