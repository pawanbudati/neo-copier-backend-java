package com.neocopier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocopier.model.Scrip;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UpstoxService {

    private static final Logger log = LoggerFactory.getLogger(UpstoxService.class);

    @Value("${upstox.api.key:}")
    private String apiKey;

    @Value("${upstox.api.secret:}")
    private String apiSecret;

    @Value("${upstox.redirect.uri:http://localhost:3000/api/upstox/callback}")
    private String redirectUri;

    @Value("${upstox.access.token:}")
    private String initialAccessToken;

    private String accessToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> instrumentKeyCache = new ConcurrentHashMap<>();

    public UpstoxService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        if (initialAccessToken != null && !initialAccessToken.trim().isEmpty()) {
            this.accessToken = initialAccessToken.trim();
            log.info("[UpstoxService] Initialized with configured access token.");
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public boolean hasValidToken() {
        return accessToken != null && !accessToken.trim().isEmpty();
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String token) {
        if (token != null) {
            this.accessToken = token.trim();
            log.info("[UpstoxService] Upstox Access Token updated successfully.");
        }
    }

    public String getAuthUrl() {
        if (!isConfigured()) {
            return "";
        }
        return "https://api.upstox.com/v2/login/authorization/dialog" +
                "?response_type=code" +
                "&client_id=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8) +
                "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);
    }

    public Map<String, Object> exchangeCodeForToken(String code) {
        if (code == null || code.trim().isEmpty()) {
            return Map.of("success", false, "error", "Authorization code is required");
        }
        try {
            String formBody = "code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                    "&client_id=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8) +
                    "&client_secret=" + URLEncoder.encode(apiSecret, StandardCharsets.UTF_8) +
                    "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8) +
                    "&grant_type=authorization_code";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.upstox.com/v2/login/authorization/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                if (root.has("access_token")) {
                    String token = root.get("access_token").asText();
                    setAccessToken(token);
                    String userName = root.has("user_name") ? root.get("user_name").asText() : "User";
                    return Map.of("success", true, "accessToken", token, "userName", userName);
                }
            }
            log.error("[UpstoxService] Token exchange failed with status {}: {}", resp.statusCode(), resp.body());
            return Map.of("success", false, "error", "Failed to exchange token. Upstox status " + resp.statusCode());
        } catch (Exception e) {
            log.error("[UpstoxService] Exception exchanging code for token: {}", e.getMessage(), e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    public String resolveInstrumentKey(String token, Scrip scrip) {
        if (token == null) return null;

        // Check index mappings
        String tokenUpper = token.toUpperCase();
        if (tokenUpper.contains("NIFTY 50") || tokenUpper.equals("NIFTY")) {
            return "NSE_INDEX|Nifty 50";
        }
        if (tokenUpper.contains("BANK NIFTY") || tokenUpper.contains("BANKNIFTY")) {
            return "NSE_INDEX|Nifty Bank";
        }
        if (tokenUpper.contains("SENSEX") || tokenUpper.contains("BSX")) {
            return "BSE_INDEX|SENSEX";
        }

        if (scrip == null) {
            return instrumentKeyCache.get(token);
        }

        if (instrumentKeyCache.containsKey(token)) {
            return instrumentKeyCache.get(token);
        }

        // Standard Instrument Key format logic
        String symbol = scrip.getTradingSymbol();
        String exchange = scrip.getExchange() != null ? scrip.getExchange().toUpperCase() : "NSE";

        String instKey = null;
        if ("NFO".equalsIgnoreCase(exchange) || "BFO".equalsIgnoreCase(exchange) || "F&O".equalsIgnoreCase(scrip.getSegment())) {
            instKey = "NSE_FO|" + symbol;
        } else if ("BSE".equalsIgnoreCase(exchange)) {
            instKey = "BSE_EQ|" + symbol;
        } else {
            instKey = "NSE_EQ|" + symbol;
        }

        instrumentKeyCache.put(token, instKey);
        return instKey;
    }

    public List<Map<String, Object>> fetchHistoricalCandles(String instrumentKey, String timeframe) {
        if (instrumentKey == null || instrumentKey.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String interval = switch (timeframe != null ? timeframe : "1m") {
            case "30m", "1h" -> "1minute";
            case "day", "1d" -> "day";
            default -> "1minute";
        };

        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(7);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        String toDateStr = today.format(fmt);
        String fromDateStr = fromDate.format(fmt);

        String encodedKey = URLEncoder.encode(instrumentKey, StandardCharsets.UTF_8);
        String url = String.format("https://api.upstox.com/v2/historical-candle/%s/%s/%s/%s",
                encodedKey, interval, toDateStr, fromDateStr);

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json");

            if (hasValidToken()) {
                reqBuilder.header("Authorization", "Bearer " + accessToken);
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                if ("success".equalsIgnoreCase(root.path("status").asText()) && root.path("data").has("candles")) {
                    JsonNode candlesNode = root.path("data").path("candles");
                    List<Map<String, Object>> bars = new ArrayList<>();

                    for (JsonNode candle : candlesNode) {
                        if (candle.isArray() && candle.size() >= 5) {
                            String timestampStr = candle.get(0).asText();
                            double open = candle.get(1).asDouble();
                            double high = candle.get(2).asDouble();
                            double low = candle.get(3).asDouble();
                            double close = candle.get(4).asDouble();

                            long epochSec = parseTimestampToEpochSec(timestampStr);

                            Map<String, Object> bar = new HashMap<>();
                            bar.put("time", epochSec);
                            bar.put("open", open);
                            bar.put("high", high);
                            bar.put("low", low);
                            bar.put("close", close);
                            bars.add(bar);
                        }
                    }

                    // Upstox returns newest first; reverse so older candles are first
                    Collections.reverse(bars);
                    log.info("[UpstoxService] Fetched {} historical candles for {}", bars.size(), instrumentKey);
                    return bars;
                }
            } else {
                log.warn("[UpstoxService] Historical candles request status {}: {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.error("[UpstoxService] Exception fetching historical candles for {}: {}", instrumentKey, e.getMessage());
        }

        return Collections.emptyList();
    }

    private long parseTimestampToEpochSec(String timestampStr) {
        try {
            if (timestampStr.contains("T")) {
                ZonedDateTime zdt = ZonedDateTime.parse(timestampStr);
                return zdt.toEpochSecond();
            }
            return Long.parseLong(timestampStr);
        } catch (Exception e) {
            return System.currentTimeMillis() / 1000;
        }
    }
}
