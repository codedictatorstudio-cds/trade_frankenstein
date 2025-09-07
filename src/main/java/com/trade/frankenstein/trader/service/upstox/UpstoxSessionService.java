package com.trade.frankenstein.trader.service.upstox;

import com.trade.frankenstein.trader.common.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Holds the active Upstox access token and can initiate a token refresh request.
 * No reflection, no Object usage.
 */
@Component
public class UpstoxSessionService {

    @Autowired
    private UpstoxAuthClient authClient;

    @Value("${upstox.client-id}")
    private String appId;        // The UUID/path part used by the Access Token Request endpoint

    @Value("${upstox.client-secret}")
    private String clientSecret; // Your app's client secret

    private final AtomicReference<String> accessToken = new AtomicReference<>("");
    private final AtomicReference<Instant> updatedAt = new AtomicReference<>(Instant.EPOCH);


    /**
     * Pass this to UpstoxClient so each HTTP call uses the latest token.
     */
    public Supplier<String> tokenSupplier() {
        return accessToken::get;
    }

    /**
     * Called by your notifier webhook handler after Upstox posts the new token.
     */
    public void updateAccessToken(String newToken) {
        if (newToken == null || newToken.isBlank()) return;
        accessToken.set(newToken);
        updatedAt.set(Instant.now());
    }

    /**
     * Initiates the Upstox Access Token Request (user receives approval prompt).
     * The actual token arrives asynchronously at your notifier webhook.
     */
    public Result<Void> refreshAccessToken() {
        return authClient.requestAccessToken(appId, clientSecret);
    }

    /**
     * Optional observability.
     */
    public String currentToken() {
        return accessToken.get();
    }

    public Instant lastUpdatedAt() {
        return updatedAt.get();
    }
}
