package com.trade.frankenstein.trader.common.exception;

import com.trade.frankenstein.trader.common.Result;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class Http {
    private Http() {
    }

    public static <T> ResponseEntity<?> from(Result<T> r) {
        if (r == null) return ResponseEntity.internalServerError().body("Result is null");

        if (r.isSuccess()) {
            return ResponseEntity.ok(r.getData());
        } else {
            String errorCode = r.getErrorCode();
            if (errorCode == null) {
                return ResponseEntity.badRequest().body(r.getError());
            }

            // Map error codes to appropriate HTTP status codes
            HttpStatus status = switch (errorCode) {
                case "ERR-AUTH-001", "ERR-AUTH-003" -> HttpStatus.UNAUTHORIZED;
                case "ERR-AUTH-002" -> HttpStatus.FORBIDDEN;
                case "ERR-DB-001", "ERR-NOT-FOUND" -> HttpStatus.NOT_FOUND;
                case "ERR-DB-002", "ERR-SYS-001", "ERR-SYS-002" -> HttpStatus.INTERNAL_SERVER_ERROR;
                case "ERR-VAL-001", "ERR-VAL-002", "ERR-VAL-003",
                     "ERR-REQ-001", "ERR-REQ-003", "ERR-REQ-004" -> HttpStatus.BAD_REQUEST;
                case "ERR-REQ-002" -> HttpStatus.METHOD_NOT_ALLOWED;
                case "ERR-TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
                default -> HttpStatus.BAD_REQUEST;
            };

            return ResponseEntity.status(status).body(new ErrorResponse(r.getErrorCode(), r.getError(), r.getTimestamp()));
        }
    }

    /**
     * Simple error response structure that will be returned to clients
     */
    private static record ErrorResponse(String code, String message, java.time.Instant timestamp) {}
}
