package com.trade.frankenstein.trader.common.exception;

/**
 * Exception thrown when an entity is not found in the system.
 */
public class EntityNotFoundException extends BaseTradeException {
    private static final String DEFAULT_ERROR_CODE = "ERR-DB-001";

    public EntityNotFoundException(String message) {
        super(message);
    }

    public EntityNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public EntityNotFoundException(String entityType, Long id) {
        super(String.format("%s with id %d not found", entityType, id));
    }

    public EntityNotFoundException(String entityType, String identifier) {
        super(String.format("%s with identifier %s not found", entityType, identifier));
    }

    @Override
    protected String getDefaultErrorCode() {
        return DEFAULT_ERROR_CODE;
    }
}
