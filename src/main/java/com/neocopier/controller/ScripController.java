package com.neocopier.controller;

import com.neocopier.client.KotakFeedWebSocketClient;
import com.neocopier.model.Account;
import com.neocopier.service.AccountService;
import com.neocopier.service.ScripService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ScripController {

    private final ScripService scripService;
    private final AccountService accountService;
    private final KotakFeedWebSocketClient webSocketClient;

    public ScripController(ScripService scripService, AccountService accountService, KotakFeedWebSocketClient webSocketClient) {
        this.scripService = scripService;
        this.accountService = accountService;
        this.webSocketClient = webSocketClient;
    }

    @GetMapping("/api/search")
    public Map<String, Object> search(@RequestParam(value = "q", defaultValue = "") String query) {
        Account activeAccount = accountService.getFirstActiveAccount().orElse(null);
        return scripService.searchScrips(query, activeAccount, webSocketClient.getLastPrices());
    }

    @GetMapping("/api/scrips/history")
    public List<Map<String, Object>> getScripHistory(@RequestParam(value = "token", defaultValue = "") String token,
                                                     @RequestParam(value = "timeframe", defaultValue = "1m") String timeframe) {
        return scripService.getOhlcHistory(token, timeframe);
    }

    @GetMapping("/api/scrips/status")
    public Map<String, Object> getScripStatus() {
        return scripService.getScripStatus();
    }

    @PostMapping("/api/scrips/load")
    public ResponseEntity<Map<String, Object>> loadScrips(@RequestBody(required = false) Map<String, Object> body) {
        String category = body != null ? (String) body.getOrDefault("category", body.get("key")) : null;
        Account activeAccount = accountService.getFirstActiveAccount().orElse(null);

        Map<String, Object> res;
        if (category != null) {
            res = scripService.loadScripCategory(category, activeAccount);
        } else {
            int count = scripService.populateMemoryCache();
            res = Map.of("success", true, "count", count);
        }

        if (!Boolean.TRUE.equals(res.get("success"))) {
            return ResponseEntity.status(500).body(Map.of("error", res.getOrDefault("error", "Failed to load scrips")));
        }
        return ResponseEntity.ok(res);
    }

    @PostMapping("/api/scrips/clear")
    public Map<String, Object> clearScrips(@RequestBody(required = false) Map<String, Object> body) {
        String category = body != null ? (String) body.getOrDefault("category", body.get("key")) : null;
        if (category != null) {
            return scripService.clearScripCategory(category);
        } else {
            return scripService.clearAllScrips();
        }
    }

    @GetMapping("/api/scrips/cache/status")
    public Map<String, Object> getCacheStatus() {
        return scripService.getCacheStatus();
    }

    @PostMapping("/api/scrips/cache")
    public Map<String, Object> populateCache() {
        int count = scripService.populateMemoryCache();
        return Map.of("success", true, "count", count, "status", scripService.getCacheStatus());
    }
}
