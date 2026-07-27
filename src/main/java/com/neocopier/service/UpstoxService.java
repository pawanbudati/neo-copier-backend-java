package com.neocopier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocopier.model.Scrip;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
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
        loadDotEnvProperties();
        if (initialAccessToken != null && !initialAccessToken.trim().isEmpty() && (accessToken == null || accessToken.isEmpty())) {
            this.accessToken = initialAccessToken.trim();
        }
        log.info("[UpstoxService] Initialized. Configured: {}, Has Token: {}", isConfigured(), hasValidToken());
    }

    private void loadDotEnvProperties() {
        File[] possibleEnvFiles = new File[]{
                new File(".env"),
                new File("../.env"),
                new File("../neo-copier-backend-py/.env")
        };

        for (File file : possibleEnvFiles) {
            if (file.exists()) {
                try {
                    List<String> lines = Files.readAllLines(file.toPath());
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                        if (trimmed.startsWith("UPSTOX_API_KEY=")) {
                            String val = parseEnvValue(trimmed.substring("UPSTOX_API_KEY=".length()));
                            if (val != null && !val.isEmpty()) apiKey = val;
                        } else if (trimmed.startsWith("UPSTOX_API_SECRET=")) {
                            String val = parseEnvValue(trimmed.substring("UPSTOX_API_SECRET=".length()));
                            if (val != null && !val.isEmpty()) apiSecret = val;
                        } else if (trimmed.startsWith("UPSTOX_REDIRECT_URI=")) {
                            String val = parseEnvValue(trimmed.substring("UPSTOX_REDIRECT_URI=".length()));
                            if (val != null && !val.isEmpty()) redirectUri = val;
                        } else if (trimmed.startsWith("UPSTOX_ACCESS_TOKEN=")) {
                            String val = parseEnvValue(trimmed.substring("UPSTOX_ACCESS_TOKEN=".length()));
                            if (val != null && !val.isEmpty()) accessToken = val;
                        }
                    }
                } catch (Exception e) {
                    log.warn("[UpstoxService] Error parsing env file {}: {}", file.getPath(), e.getMessage());
                }
            }
        }
    }

    private String parseEnvValue(String raw) {
        if (raw == null) return "";
        String val = raw.trim();
        if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
            if (val.length() >= 2) {
                val = val.substring(1, val.length() - 1);
            }
        }
        return val.trim();
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

    public String getApiKey() {
        return apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setAccessToken(String token) {
        if (token != null) {
            this.accessToken = token.trim();
            updateDotEnvFile();
            log.info("[UpstoxService] Upstox Access Token updated successfully.");
        }
    }

    public synchronized Map<String, Object> saveConfig(String newApiKey, String newApiSecret, String newRedirectUri, String newAccessToken) {
        if (newApiKey != null) this.apiKey = newApiKey.trim();
        if (newApiSecret != null) this.apiSecret = newApiSecret.trim();
        if (newRedirectUri != null && !newRedirectUri.trim().isEmpty()) this.redirectUri = newRedirectUri.trim();
        if (newAccessToken != null) this.accessToken = newAccessToken.trim();

        updateDotEnvFile();
        log.info("[UpstoxService] Config updated via UI. Configured: {}, Has Token: {}", isConfigured(), hasValidToken());
        return getConfigMap();
    }

    public Map<String, Object> getConfigMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("apiKey", apiKey != null ? apiKey : "");
        map.put("apiSecret", apiSecret != null ? apiSecret : "");
        map.put("redirectUri", redirectUri != null ? redirectUri : "");
        map.put("accessToken", accessToken != null ? accessToken : "");
        map.put("isConfigured", isConfigured());
        map.put("hasToken", hasValidToken());
        map.put("authUrl", getAuthUrl());
        return map;
    }

    private synchronized void updateDotEnvFile() {
        File envFile = new File(".env");
        try {
            List<String> lines = envFile.exists() ? Files.readAllLines(envFile.toPath()) : new ArrayList<>();
            Map<String, String> currentVars = new LinkedHashMap<>();

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    currentVars.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
                }
            }

            if (apiKey != null && !apiKey.isEmpty()) currentVars.put("UPSTOX_API_KEY", apiKey);
            if (apiSecret != null && !apiSecret.isEmpty()) currentVars.put("UPSTOX_API_SECRET", apiSecret);
            if (redirectUri != null && !redirectUri.isEmpty()) currentVars.put("UPSTOX_REDIRECT_URI", redirectUri);
            if (accessToken != null && !accessToken.isEmpty()) currentVars.put("UPSTOX_ACCESS_TOKEN", accessToken);

            List<String> newLines = new ArrayList<>();
            for (Map.Entry<String, String> entry : currentVars.entrySet()) {
                newLines.add(entry.getKey() + "=" + entry.getValue());
            }

            Files.write(envFile.toPath(), newLines, StandardCharsets.UTF_8);
            log.info("[UpstoxService] .env file saved successfully.");
        } catch (Exception e) {
            log.error("[UpstoxService] Failed to write .env file: {}", e.getMessage());
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
