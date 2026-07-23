package com.neocopier.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocopier.model.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

@Component
public class KotakFeedWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(KotakFeedWebSocketClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocket webSocket;
    private boolean connected = false;
    private String activeAccountId = null;

    private final Set<String> subscribedTokens = new CopyOnWriteArraySet<>();
    private final Map<String, Integer> subscriptionCounts = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> lastPrices = new ConcurrentHashMap<>();
    private final Set<Consumer<Map<String, Object>>> tickListeners = new CopyOnWriteArraySet<>();

    public synchronized void connect(Account account) {
        if (account == null || account.getNeoToken() == null) {
            log.warn("[FeedWS] Cannot connect WebSocket: inactive account or missing neoToken.");
            return;
        }

        if (connected && Objects.equals(activeAccountId, account.getId())) {
            return;
        }

        disconnect();

        try {
            String wsUrl = String.format("wss://mis.kotaksecurities.com/websocket/v1?neo_token=%s&sid=%s",
                    account.getNeoToken(), account.getSid());

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            WebSocket.Listener listener = new WebSocket.Listener() {
                private final StringBuilder messageBuffer = new StringBuilder();

                @Override
                public void onOpen(WebSocket ws) {
                    log.info("[FeedWS] Connected to Kotak WebSocket for account {}", account.getNickname());
                    connected = true;
                    activeAccountId = account.getId();
                    ws.request(1);
                    resubscribeAll(account);
                }

                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    messageBuffer.append(data);
                    if (last) {
                        String fullMsg = messageBuffer.toString();
                        messageBuffer.setLength(0);
                        handleIncomingMessage(fullMsg);
                    }
                    ws.request(1);
                    return null;
                }

                @Override
                public void onError(WebSocket ws, Throwable error) {
                    log.error("[FeedWS] WebSocket error: {}", error.getMessage());
                    connected = false;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    log.info("[FeedWS] WebSocket closed: {} - {}", statusCode, reason);
                    connected = false;
                    return null;
                }
            };

            this.webSocket = client.newWebSocketBuilder()
                    .header("User-Agent", "Mozilla/5.0")
                    .buildAsync(URI.create(wsUrl), listener)
                    .join();

        } catch (Exception e) {
            log.error("[FeedWS] Failed to connect WebSocket: {}", e.getMessage());
            connected = false;
        }
    }

    public synchronized void disconnect() {
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Disconnect requested");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        connected = false;
        activeAccountId = null;
    }

    public boolean isConnected() {
        return connected && !subscribedTokens.isEmpty();
    }

    public Map<String, Map<String, Object>> getLastPrices() {
        return lastPrices;
    }

    public void addTickListener(Consumer<Map<String, Object>> listener) {
        tickListeners.add(listener);
    }

    public void removeTickListener(Consumer<Map<String, Object>> listener) {
        tickListeners.remove(listener);
    }

    public void subscribeTokens(Account account, List<String> tokens) {
        for (String t : tokens) {
            if (t != null && !t.isEmpty()) {
                subscriptionCounts.merge(t, 1, Integer::sum);
                subscribedTokens.add(t);
            }
        }
        if (connected && webSocket != null && account != null) {
            sendSubscriptionCommand("subscribe", tokens);
        }
    }

    public void unsubscribeTokens(List<String> tokens) {
        List<String> toUnsub = new ArrayList<>();
        for (String t : tokens) {
            if (t != null && !t.isEmpty()) {
                int count = subscriptionCounts.getOrDefault(t, 0);
                if (count <= 1) {
                    subscriptionCounts.remove(t);
                    subscribedTokens.remove(t);
                    toUnsub.add(t);
                } else {
                    subscriptionCounts.put(t, count - 1);
                }
            }
        }
        if (connected && webSocket != null && !toUnsub.isEmpty()) {
            sendSubscriptionCommand("unsubscribe", toUnsub);
        }
    }

    private void resubscribeAll(Account account) {
        if (!subscribedTokens.isEmpty()) {
            sendSubscriptionCommand("subscribe", new ArrayList<>(subscribedTokens));
        }
    }

    private void sendSubscriptionCommand(String type, List<String> tokens) {
        try {
            Map<String, Object> payload = Map.of(
                    "type", "stock_feed",
                    "action", type,
                    "tokens", tokens
            );
            String json = objectMapper.writeValueAsString(payload);
            webSocket.sendText(json, true);
        } catch (Exception e) {
            log.error("[FeedWS] Failed to send subscription payload: {}", e.getMessage());
        }
    }

    private void handleIncomingMessage(String text) {
        try {
            if (!text.trim().startsWith("{") && !text.trim().startsWith("[")) return;

            Object parsed = objectMapper.readValue(text, Object.class);
            List<Map<String, Object>> ticks = new ArrayList<>();

            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        ticks.add((Map<String, Object>) map);
                    }
                }
            } else if (parsed instanceof Map<?, ?> map) {
                Object data = map.get("data");
                if (data instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> itemMap) {
                            ticks.add((Map<String, Object>) itemMap);
                        }
                    }
                } else {
                    ticks.add((Map<String, Object>) map);
                }
            }

            for (Map<String, Object> raw : ticks) {
                Map<String, Object> tick = normalizeTick(raw);
                if (tick != null && tick.containsKey("token")) {
                    String token = (String) tick.get("token");
                    lastPrices.put(token, tick);
                    for (Consumer<Map<String, Object>> listener : tickListeners) {
                        try {
                            listener.accept(tick);
                        } catch (Exception e) {
                            log.error("[FeedWS] Listener error: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[FeedWS] Parse tick notice: {}", e.getMessage());
        }
    }

    private Map<String, Object> normalizeTick(Map<String, Object> raw) {
        String token = parseString(raw, List.of("instrument_token", "tk", "token", "trading_symbol", "ts"));
        if (token == null || token.isEmpty()) return null;

        double ltp = parseDouble(raw.get("last_traded_price"), raw.get("ltp"), raw.get("lp"), raw.get("iv"));
        if (ltp <= 0) return null;

        double prevClose = parseDouble(raw.get("prev_day_close"), raw.get("close"), raw.get("ic"), raw.get("c"));
        double change = parseDouble(raw.get("change"), raw.get("cng"));
        double changePct = parseDouble(raw.get("net_change_percentage"), raw.get("nc"), raw.get("changePct"));

        if (change == 0 && ltp > 0 && prevClose > 0) {
            change = Math.round((ltp - prevClose) * 100.0) / 100.0;
            changePct = Math.round(((ltp - prevClose) / prevClose * 100.0) * 100.0) / 100.0;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("ltp", ltp);
        result.put("change", change);
        result.put("changePct", changePct);
        return result;
    }

    private String parseString(Map<String, Object> map, List<String> keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v != null && !v.toString().isEmpty()) return v.toString();
        }
        return null;
    }

    private double parseDouble(Object... values) {
        for (Object v : values) {
            if (v != null) {
                try {
                    return Double.parseDouble(v.toString());
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0.0;
    }
}
