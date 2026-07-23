package com.neocopier.service;

import com.neocopier.model.Watchlist;
import com.neocopier.repository.WatchlistRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;

    public WatchlistService(WatchlistRepository watchlistRepository) {
        this.watchlistRepository = watchlistRepository;
    }

    public List<Watchlist> getWatchlist() {
        return watchlistRepository.findAll();
    }

    public Watchlist addWatchlistItem(Watchlist item) {
        if (item.getAddedAt() == null || item.getAddedAt().isEmpty()) {
            item.setAddedAt(LocalDateTime.now().toString());
        }
        return watchlistRepository.save(item);
    }

    public void removeWatchlistItem(String scriptToken) {
        watchlistRepository.deleteById(scriptToken);
    }
}
