package com.trade.frankenstein.trader.common;

import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Getter
@ToString
public final class Result<T> {

    private final boolean success;
    private final T data;
    private final String error;
    private final String errorCode;
    private final Instant timestamp;

    private Result(boolean success, T data, String error, String errorCode, Instant timestamp) {
        this.success = success;
        this.data = data;
        this.error = error;
        this.errorCode = errorCode;
        this.timestamp = (timestamp == null ? Instant.now() : timestamp);
    }

    // ---------- factories ----------
    public static <T> Result<T> ok(T data) {
        return new Result<>(true, data, null, null, Instant.now());
    }

    public static <T> Result<T> ok() {
        return new Result<>(true, null, null, null, Instant.now());
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(false, null, message, null, Instant.now());
    }

    public static <T> Result<T> fail(String code, String message) {
        return new Result<>(false, null, message, code, Instant.now());
    }

    public static <T> Result<T> fail(Throwable t) {
        String msg = (t == null)
                ? "Unknown error"
                : (t.getMessage() == null ? t.toString() : t.getMessage());
        return new Result<>(false, null, msg, null, Instant.now());
    }

    // ---------- convenience helpers ----------

    /**
     * Convenience alias: true when successful.
     */
    public boolean isOk() {
        return success;
    }

    /**
     * Convenience alias for the payload (same as getData()).
     */
    public T get() {
        return data;
    }

    /**
     * Explicit getter for error text (Lombok would also generate this).
     */
    public String getError() {
        return error;
    }

    /**
     * True when failed.
     */
    public boolean isFailure() {
        return !success;
    }

    /**
     * Returns data if OK, otherwise the provided fallback value.
     */
    public T getOrElse(T fallback) {
        return (success && data != null) ? data : fallback;
    }

    /**
     * Returns data if OK, otherwise value from supplier.
     */
    public T orElseGet(Supplier<? extends T> supplier) {
        return (success && data != null) ? data : supplier.get();
    }

    /**
     * Runs consumer if OK.
     */
    public void ifSuccess(Consumer<? super T> consumer) {
        if (success) consumer.accept(data);
    }

    /**
     * Runs consumer if failed.
     */
    public void ifFailure(Consumer<? super String> consumer) {
        if (isFailure()) consumer.accept(error);
    }

    /**
     * Maps the payload when OK; propagates failure otherwise.
     */
    public <R> Result<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (isFailure()) return Result.fail(errorCode, error);
        return Result.ok(mapper.apply(data));
    }

    /**
     * Flat-maps the payload when OK; propagates failure otherwise.
     */
    public <R> Result<R> flatMap(Function<? super T, Result<R>> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        if (isFailure()) return Result.fail(errorCode, error);
        return Objects.requireNonNull(mapper.apply(data));
    }

    // ---------- static utilities ----------

    /**
     * If the given result is OK, returns its value; otherwise returns the fallback
     * produced by {@code fallbackSupplier}. Treats a null Result as not OK.
     */
    public static <T> T okOrElse(Result<T> r, Supplier<? extends T> fallbackSupplier) {
        Objects.requireNonNull(fallbackSupplier, "fallbackSupplier");
        return isOk(r) ? r.getData() : fallbackSupplier.get();
    }

    /**
     * Null-safe convenience for checking a result without NPEs.
     */
    public static boolean isOk(Result<?> r) {
        return r != null && r.isSuccess();
    }

    /**
     * Null-safe convenience for extracting error text.
     */
    public static String errorOf(Result<?> r) {
        return (r == null) ? "Result is null" : r.getError();
    }
}
