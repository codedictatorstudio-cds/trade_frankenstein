package com.trade.frankenstein.trader.enums;

public enum OrderStatus {
    NEW,                // created locally
    PENDING_SUBMIT,     // en route to broker
    SUBMITTED,          // accepted by broker
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
    EXPIRED
}

