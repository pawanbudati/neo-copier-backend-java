package com.neocopier.controller;

import com.neocopier.model.Watchlist;
import com.neocopier.service.WatchlistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping
    public List<Watchlist> getWatchlist() {
        return watchlistService.getWatchlist();
    }

    @PostMapping
    public ResponseEntity<Map<String, Boolean>> addWatchlist(@RequestBody Watchlist body) {
        if (body.getScriptToken() == null || body.getTradingSymbol() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", false));
        }
        watchlistService.addWatchlistItem(body);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/{script_token}")
    public Map<String, Boolean> removeWatchlist(@PathVariable("script_token") String scriptToken) {
        watchlistService.removeWatchlistItem(scriptToken);
        return Map.of("success", true);
    }
}
