package com.trade.frankenstein.trader.service.jobs;

import com.trade.frankenstein.trader.common.Result;
import com.trade.frankenstein.trader.service.streaming.StreamGateway;
import com.trade.frankenstein.trader.service.upstox.UpstoxSessionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * TokenRefreshJob — typed Upstox token keepalive.
 * <p>
 * What it does:
 * - runOnce(): calls broker.refreshAccessToken() and emits an SSE "auth.token" event.
 * - refreshDaily(): scheduled at 03:20 IST (default) — Upstox access tokens expire 03:30 IST daily.
 * <p>
 * Config (application.yml):
 * trade:
 * upstox:
 * refresh:
 * enabled: true
 * on-startup: true
 * cron: "0 20 3 * * *"         # 03:20 every day
 * app:
 * timezone: "Asia/Kolkata"
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenRefreshJob {

    private final StreamGateway stream;
    private final UpstoxSessionService session; // set by UpstoxConfig

    @Value("${trade.upstox.refresh.enabled:true}")
    private boolean enabled;

    @Value("${trade.upstox.refresh.on-startup:true}")
    private boolean runOnStartup;

    @Value("${app.timezone:Asia/Kolkata}")
    private String appZone;

    // Kept only for observability
    private volatile Instant lastRunAt = null;
    private volatile String lastStatus = "never";
    private volatile String lastError = null;

    @PostConstruct
    public void init() {
        if (enabled && runOnStartup) {
            try {
                runOnce(); // best-effort kick
            } catch (Throwable t) {
                log.warn("Initial token refresh failed: {}", t.toString());
            }
        }
    }

    /**
     * Manual trigger (e.g., from an admin endpoint).
     */
    public void runOnce() {
        lastRunAt = Instant.now();

        if (!enabled) {
            lastStatus = "disabled";
            lastError = null;
            publish();
            Result.ok("token:disabled");
            return;
        }

        try {
            Result<Void> r = session.refreshAccessToken();
            if (r.isOk()) {
                lastStatus = "ok:refreshed";
                lastError = null;
                publish();
                Result.ok("token:refreshed");
            } else {
                lastStatus = "error";
                lastError = r.getError();
                publish();
                Result.fail("BROKER_ERROR", r.getError());
            }
        } catch (Exception e) {
            lastStatus = "error";
            lastError = e.getMessage();
            publish();
            log.warn("Token refresh error", e);
            Result.fail("BROKER_ERROR", e.getMessage());
        }
    }

    /**
     * Daily refresh at 03:20 (IST by default).
     * Adjust with: trade.upstox.refresh.cron and app.timezone.
     */
    @Scheduled(cron = "${trade.upstox.refresh.cron:0 20 3 * * *}", zone = "${app.timezone:Asia/Kolkata}")
    public void refreshDaily() {
        runOnce();
    }

    private void publish() {
        try {
            stream.send("auth.token", new TokenRefreshEvent(lastStatus, lastRunAt, lastError));
        } catch (Throwable ignored) {
        }
    }

    /**
     * @param status "ok:refreshed" | "error" | "disabled" | "never"
     * @param error  null unless status == "error"
     */
    public record TokenRefreshEvent(String status, Instant asOf, String error) {
    }
}
