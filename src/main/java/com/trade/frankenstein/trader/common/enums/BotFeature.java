package com.trade.frankenstein.trader.common.enums;

import org.togglz.core.Feature;
import org.togglz.core.annotation.*;

@Label("Bot Features")
public enum BotFeature implements Feature {

    @Label("Allow re-strike after SL")
    RESTRIKE_ENABLED,

    @Label("PCR tilt bias")
    PCR_TILT,

    @Label("PM +1 strangle bias")
    STRANGLE_PM_PLUS1,

    @Label("Quiet ATM straddle mode")
    ATM_STRADDLE_QUIET
}
