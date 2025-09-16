package com.trade.frankenstein.trader.common.exception;

/**
 * Exception for validation errors in the application.
 */
public class ValidationException extends BaseTradeException {
    private static final String DEFAULT_ERROR_CODE = "ERR-VAL-001";

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ValidationException(String errorCode, String message) {
        super(errorCode, message);
    }

    public ValidationException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    @Override
    protected String getDefaultErrorCode() {
        return DEFAULT_ERROR_CODE;
    }
}
