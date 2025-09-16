package com.trade.frankenstein.trader.ui.bridge;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.UI;

public class GlobalModeChangedEvent extends ComponentEvent<UI> {

    private final boolean sandbox;

    public GlobalModeChangedEvent(UI source, boolean sandbox) {
        super(source, false);
        this.sandbox = sandbox;
    }

    public boolean isSandbox() {
        return sandbox;
    }
}