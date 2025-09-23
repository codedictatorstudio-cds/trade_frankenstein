package com.trade.frankenstein.trader.jobs;

import com.trade.frankenstein.trader.service.RiskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes today's realized PnL from the broker and pushes
 * fresh risk summary + circuit state to the UI via SSE.
 * <p>
 * Configure (optional):
 * trade.timezone=Asia/Kolkata
 * trade.pnl.rollup.intraday-cron=0 0/1 9-15 ? * MON-FRI
 * trade.pnl.rollup.daily-cron=0 35 15 ? * MON-FRI
 * <p>
 * Ensure @EnableScheduling is present in the application.
 */
@Component
@Slf4j
public class PnlRollupJob {

    @Autowired
    private RiskService risk;

    /**
     * Intraday refresh — default: every minute during market hours (Mon–Fri).
     */
    @Scheduled(
            cron = "${trade.pnl.rollup.intraday-cron:0 0/1 9-15 ? * MON-FRI}",
            zone = "${trade.timezone:Asia/Kolkata}"
    )
    public void rollupIntraday() {
        long t0 = System.currentTimeMillis();
        try {
            risk.refreshDailyLossFromBroker(); // pulls realized PnL and updates internal counters
            risk.getSummary();                  // builds & SSE-sends "risk.summary"
            risk.getCircuitState();             // SSE-sends "risk.circuit"
            log.info("PnL intraday refresh done ({} ms)", System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            log.warn("PnL intraday refresh error: {}", t.getMessage(), t);
        }
    }

    /**
     * End-of-day refresh — default: 15:35 IST after close.
     */
    @Scheduled(
            cron = "${trade.pnl.rollup.daily-cron:0 35 15 ? * MON-FRI}",
            zone = "${trade.timezone:Asia/Kolkata}"
    )
    public void rollupDaily() {
        long t0 = System.currentTimeMillis();
        try {
            risk.refreshDailyLossFromBroker();
            risk.getSummary();
            risk.getCircuitState();
            log.info("PnL daily refresh done ({} ms)", System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            log.warn("PnL daily refresh error: {}", t.getMessage(), t);
        }
    }
}
