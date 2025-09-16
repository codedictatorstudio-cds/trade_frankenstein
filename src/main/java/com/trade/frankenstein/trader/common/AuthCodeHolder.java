package com.trade.frankenstein.trader.common;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Global in-JVM holder for the OAuth "code".
 * NOTE: This is process-wide. If you ever run multi-user,
 * prefer HttpSession or a per-session store instead.
 */
public final class AuthCodeHolder {

    private static final AuthCodeHolder INSTANCE = new AuthCodeHolder();

    private final AtomicReference<String> codeRef = new AtomicReference<>();
    private final AtomicReference<Boolean> loginRef = new AtomicReference<>();

    private AuthCodeHolder() {
    }

    /** Get the singleton instance. */
    public static AuthCodeHolder getInstance() {
        return INSTANCE;
    }

    /** Save/overwrite the auth code (trims; ignores null/blank). */
    public void set(String code) {
        if (code == null) return;
        String c = code.trim();
        if (c.isEmpty()) return;
        codeRef.set(c);
        loginRef.set(Boolean.TRUE);
    }

    /** Read the current code without clearing it (may be null). */
    public String peek() {
        return codeRef.get();
    }

    /** Atomically get the code and clear it (one-time use). */
    public String getAndClear() {
        String c = codeRef.getAndSet(null);
        return c;
    }

    /** Clear any stored code. */
    public void clear() {
        codeRef.set(null);
    }

    /** Is a code currently stored? */
    public boolean isPresent() {
        return codeRef.get() != null;
    }

    public boolean isLoggedIn() {
        Boolean b = loginRef.get();
        return b != null && b;
    }
}
