package com.neocopier.controller;

import com.neocopier.service.FeedService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
public class FeedController {

    private final FeedService feedService;

    public FeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/api/feed/status")
    public Map<String, Boolean> getFeedStatus() {
        return Map.of("connected", feedService.isMarketFeedConnected());
    }

    @GetMapping("/api/quotes/stream")
    public SseEmitter streamQuotes(@RequestParam(value = "tokens", defaultValue = "") String tokens) {
        return feedService.createQuoteStream(tokens);
    }
}
