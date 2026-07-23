package com.neocopier.scheduler;

import com.neocopier.model.Account;
import com.neocopier.service.AccountService;
import com.neocopier.service.ScripService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;

@Component
public class ScripScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScripScheduler.class);

    private final ScripService scripService;
    private final AccountService accountService;

    private boolean todaySyncSuccessful = false;
    private int retryAttempts = 0;

    public ScripScheduler(ScripService scripService, AccountService accountService) {
        this.scripService = scripService;
        this.accountService = accountService;
    }

    /**
     * Daily primary scheduler running at 7:00 AM every day.
     */
    @Scheduled(cron = "0 0 7 * * *")
    public void runDaily7AmSync() {
        log.info("[ScripScheduler] Starting 7:00 AM daily Nifty & Sensex options sync...");
        todaySyncSuccessful = false;
        retryAttempts = 0;
        executeSync();
    }

    /**
     * Retry scheduler running every 30 minutes between 7:00 AM and 9:00 AM.
     * Retries if the primary 7:00 AM sync failed.
     */
    @Scheduled(cron = "0 30 7-8 * * *") // Runs at 7:30 AM, 8:00 AM, 8:30 AM
    public void runRetrySync() {
        LocalTime now = LocalTime.now();
        if (!todaySyncSuccessful && now.isBefore(LocalTime.of(9, 5))) {
            retryAttempts++;
            log.info("[ScripScheduler] Retrying daily options sync (Attempt #{} at {})...", retryAttempts, now);
            executeSync();
        }
    }

    /**
     * Final retry check at 9:00 AM.
     */
    @Scheduled(cron = "0 0 9 * * *")
    public void runFinal9AmRetrySync() {
        if (!todaySyncSuccessful) {
            retryAttempts++;
            log.info("[ScripScheduler] Final 9:00 AM retry attempt for daily options sync...");
            executeSync();
        }
    }

    public Map<String, Object> executeSync() {
        Optional<Account> activeAccountOpt = accountService.getFirstActiveAccount();

        try {
            Map<String, Object> result = scripService.loadDailyIndexOptions(activeAccountOpt.orElse(null));
            if (Boolean.TRUE.equals(result.get("success"))) {
                todaySyncSuccessful = true;
                log.info("[ScripScheduler] Daily Nifty & Sensex options sync completed successfully. Loaded Nifty: {}, Sensex: {}",
                        result.get("niftyCount"), result.get("sensexCount"));
            } else {
                log.warn("[ScripScheduler] Daily options sync failed: {}", result.get("error"));
            }
            return result;
        } catch (Exception e) {
            log.error("[ScripScheduler] Exception during daily options sync: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    public boolean isTodaySyncSuccessful() {
        return todaySyncSuccessful;
    }
}
