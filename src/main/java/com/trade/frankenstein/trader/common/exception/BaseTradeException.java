package com.trade.frankenstein.trader.common.exception;

import lombok.Getter;

/**
 * Base exception class for all trading application exceptions.
 * Provides a standard way to include error codes with exceptions.
 */
@Getter
public abstract class BaseTradeException extends RuntimeException {
    /**
     * -- GETTER --
     *  Returns the error code associated with this exception.
     */
    private final String errorCode;

    public BaseTradeException(String message) {
        super(message);
        this.errorCode = getDefaultErrorCode();
    }

    public BaseTradeException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = getDefaultErrorCode();
    }

    public BaseTradeException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BaseTradeException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * Each subclass must provide a default error code.
     */
    protected abstract String getDefaultErrorCode();
}
