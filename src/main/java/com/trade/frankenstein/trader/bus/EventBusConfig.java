package com.trade.frankenstein.trader.bus;

public final class EventBusConfig {

    private EventBusConfig() {
    }

    public static final String TOPIC_TICKS = "ticks";

    public static final String TOPIC_OPTION_CHAIN = "option_chain";

    public static final String TOPIC_ADVICE = "advice";

    public static final String TOPIC_TRADE = "trade";

    public static final String TOPIC_RISK = "risk";

    public static final String TOPIC_AUDIT = "audit";

    public static final String TOPIC_DECISION = "decision";

    public static final String TOPIC_ORDER = "order";

    public static final String TOPIC_METRICS= "metrics";
}