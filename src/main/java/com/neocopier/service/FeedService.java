package com.neocopier.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocopier.client.KotakFeedWebSocketClient;
import com.neocopier.model.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);

    private final KotakFeedWebSocketClient webSocketClient;
    private final AccountService accountService;
    private final ScripService scripService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private boolean isSystemPowerOn = true;

    public FeedService(KotakFeedWebSocketClient webSocketClient,
                       AccountService accountService,
                       ScripService scripService) {
        this.webSocketClient = webSocketClient;
        this.accountService = accountService;
        this.scripService = scripService;

        // Register tick listener to record OHLC candle history
        this.webSocketClient.addTickListener(tick -> {
            if (tick.containsKey("token") && tick.containsKey("ltp")) {
                scripService.recordTick((String) tick.get("token"), (double) tick.get("ltp"));
            }
        });
    }

    public boolean isSystemPowerOn() {
        return isSystemPowerOn;
    }

    public void setSystemPowerOn(boolean powerOn) {
        this.isSystemPowerOn = powerOn;
        if (!powerOn) {
            webSocketClient.disconnect();
        } else {
            connectMarketFeed();
        }
    }

    public boolean isMarketFeedConnected() {
        return webSocketClient.isConnected();
    }

    public void connectMarketFeed() {
        if (!isSystemPowerOn) return;
        Optional<Account> firstActive = accountService.getFirstActiveAccount();
        firstActive.ifPresent(webSocketClient::connect);
    }

    public SseEmitter createQuoteStream(String tokensStr) {
        List<String> requestedTokens = tokensStr != null ? Arrays.stream(tokensStr.split(",")).filter(t -> !t.isEmpty()).toList() : Collections.emptyList();
        SseEmitter emitter = new SseEmitter(0L); // Infinite timeout

        Optional<Account> activeAcc = accountService.getFirstActiveAccount();
        if (activeAcc.isPresent()) {
            connectMarketFeed();
            webSocketClient.subscribeTokens(activeAcc.get(), requestedTokens);
        }

        // Send initial cached price ticks
        Map<String, Map<String, Object>> lastPrices = webSocketClient.getLastPrices();
        Map<String, Object> initialPayload = new HashMap<>();
        for (String t : requestedTokens) {
            if (lastPrices.containsKey(t)) {
                initialPayload.put(t, lastPrices.get(t));
            }
        }
        if (!initialPayload.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(initialPayload)));
            } catch (IOException ignored) {}
        }

        Consumer<Map<String, Object>> listener = tick -> {
            String token = (String) tick.get("token");
            if (requestedTokens.contains(token)) {
                try {
                    Map<String, Object> payload = Map.of(token, tick);
                    emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }
        };

        webSocketClient.addTickListener(listener);

        emitter.onCompletion(() -> {
            webSocketClient.removeTickListener(listener);
            webSocketClient.unsubscribeTokens(requestedTokens);
        });

        emitter.onTimeout(() -> {
            webSocketClient.removeTickListener(listener);
            webSocketClient.unsubscribeTokens(requestedTokens);
        });

        emitter.onError(e -> {
            webSocketClient.removeTickListener(listener);
            webSocketClient.unsubscribeTokens(requestedTokens);
        });

        return emitter;
    }
}
