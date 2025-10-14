package com.trade.frankenstein.trader.common.exception;

public class TradeReconciliationException extends RuntimeException {

    public TradeReconciliationException(String message) {
        super(message);
    }

    public TradeReconciliationException(String message, Throwable cause) {
        super(message, cause);
    }
}
