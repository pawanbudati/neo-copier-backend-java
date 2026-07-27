package com.neocopier.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocopier.model.Scrip;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

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
    private final Map<String, String> symbolToInstrumentKeyMap = new ConcurrentHashMap<>();
    private final Map<String, String> optionContractMap = new ConcurrentHashMap<>();
    private volatile boolean instrumentsIndexed = false;

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
        downloadAndIndexUpstoxInstrumentsAsync();
        log.info("[UpstoxService] Initialized. Configured: {}, Has Token: {}", isConfigured(), hasValidToken());
    }

    public void downloadAndIndexUpstoxInstrumentsAsync() {
        if (instrumentsIndexed) return;
        new Thread(() -> {
            log.info("[UpstoxService] Downloading Upstox Instrument Master CSV in background...");
            try {
                URL url = URI.create("https://assets.upstox.com/market-quote/instruments/exchange/complete.csv.gz").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);

                try (GZIPInputStream gzip = new GZIPInputStream(conn.getInputStream());
                     BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8))) {

                    String line;
                    boolean isHeader = true;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        if (isHeader) {
                            isHeader = false;
                            continue;
                        }
                        String[] cols = line.split(",");
                        if (cols.length >= 11) {
                            String instKey = cols[0].replace("\"", "").trim();
                            String tradingSym = cols[2].replace("\"", "").trim().toUpperCase();
                            String name = cols[3].replace("\"", "").trim().toUpperCase();
                            String strikeStr = cols[6].replace("\"", "").trim();
                            String optType = cols[10].replace("\"", "").trim().toUpperCase();

                            if (!instKey.isEmpty()) {
                                if (!tradingSym.isEmpty()) {
                                    symbolToInstrumentKeyMap.put(tradingSym, instKey);
                                }
                                // Index options by UNDERLYING|STRIKE|OPTION_TYPE (e.g. NIFTY|24000.0|CE, SENSEX|77000.0|PE)
                                if (!name.isEmpty() && !strikeStr.isEmpty() && !optType.isEmpty() && (optType.equals("CE") || optType.equals("PE"))) {
                                    try {
                                        double strike = Double.parseDouble(strikeStr);
                                        String optKey1 = String.format(Locale.US, "%s|%.1f|%s", name, strike, optType);
                                        String optKey2 = String.format(Locale.US, "%s|%.0f|%s", name, strike, optType);
                                        optionContractMap.putIfAbsent(optKey1, instKey);
                                        optionContractMap.putIfAbsent(optKey2, instKey);
                                    } catch (NumberFormatException ignored) {}
                                }
                                count++;
                            }
                        }
                    }
                    instrumentsIndexed = true;
                    log.info("[UpstoxService] Successfully indexed {} Upstox instruments and {} option contracts into memory!", count, optionContractMap.size());
                }
            } catch (Exception e) {
                log.warn("[UpstoxService] Could not index Upstox instruments: {}", e.getMessage());
            }
        }, "upstox-indexer-thread").start();
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
        if (token == null && scrip == null) return null;

        String cacheKey = (scrip != null && scrip.getScriptToken() != null) ? scrip.getScriptToken() : token;
        if (cacheKey != null && instrumentKeyCache.containsKey(cacheKey)) {
            return instrumentKeyCache.get(cacheKey);
        }

        if (scrip != null) {
            String tradingSym = scrip.getTradingSymbol() != null ? scrip.getTradingSymbol().trim().toUpperCase() : "";
            String refKey = scrip.getScripRefKey() != null ? scrip.getScripRefKey().trim().toUpperCase() : "";
            String instName = scrip.getInstrumentName() != null ? scrip.getInstrumentName().trim().toUpperCase() : "";
            String segment = scrip.getSegment() != null ? scrip.getSegment().toUpperCase() : "";

            boolean isOption = instName.contains("OPT") || segment.equals("CE") || segment.equals("PE")
                    || tradingSym.endsWith("CE") || tradingSym.endsWith("PE") || refKey.endsWith("CE") || refKey.endsWith("PE");

            if (isOption) {
                String optType = (segment.equals("CE") || segment.equals("PE")) ? segment :
                        (tradingSym.endsWith("CE") || refKey.endsWith("CE") ? "CE" : "PE");
                String name = extractUnderlyingName(tradingSym, refKey, instName);
                Double strike = extractStrikePrice(scrip);

                if (strike != null && strike > 0 && name != null) {
                    String optKey1 = String.format(Locale.US, "%s|%.1f|%s", name, strike, optType);
                    String optKey2 = String.format(Locale.US, "%s|%.0f|%s", name, strike, optType);

                    if (optionContractMap.containsKey(optKey1)) {
                        String matchedKey = optionContractMap.get(optKey1);
                        log.debug("[UpstoxService] Matched Option Contract (key1): {} -> {}", optKey1, matchedKey);
                        if (cacheKey != null) instrumentKeyCache.put(cacheKey, matchedKey);
                        return matchedKey;
                    }
                    if (optionContractMap.containsKey(optKey2)) {
                        String matchedKey = optionContractMap.get(optKey2);
                        log.debug("[UpstoxService] Matched Option Contract (key2): {} -> {}", optKey2, matchedKey);
                        if (cacheKey != null) instrumentKeyCache.put(cacheKey, matchedKey);
                        return matchedKey;
                    }
                }
            }

            // Direct tradingSymbol / refKey lookup
            if (!tradingSym.isEmpty() && symbolToInstrumentKeyMap.containsKey(tradingSym)) {
                String matchedKey = symbolToInstrumentKeyMap.get(tradingSym);
                if (cacheKey != null) instrumentKeyCache.put(cacheKey, matchedKey);
                return matchedKey;
            }
            if (!refKey.isEmpty() && symbolToInstrumentKeyMap.containsKey(refKey)) {
                String matchedKey = symbolToInstrumentKeyMap.get(refKey);
                if (cacheKey != null) instrumentKeyCache.put(cacheKey, matchedKey);
                return matchedKey;
            }

            // Index matching (only if NOT an option!)
            if (!isOption) {
                if (tradingSym.contains("NIFTY 50") || refKey.contains("NIFTY 50") || tradingSym.equals("NIFTY")) {
                    return "NSE_INDEX|Nifty 50";
                }
                if (tradingSym.contains("BANKNIFTY") || refKey.contains("BANKNIFTY") || tradingSym.contains("NIFTY BANK")) {
                    return "NSE_INDEX|Nifty Bank";
                }
                if (tradingSym.contains("SENSEX") || refKey.contains("SENSEX")) {
                    return "BSE_INDEX|SENSEX";
                }
            }
        }

        if (scrip == null) {
            if (token != null && token.contains("|")) {
                return token;
            }
            if (token != null && token.matches("\\d+")) {
                return "NSE_FO|" + token;
            }
            String clean = token != null ? token.replaceAll("-(EQ|BE|BZ|SM)$", "") : "";
            return "NSE_EQ|" + clean;
        }

        String symbol = scrip.getTradingSymbol();
        if (symbol == null || symbol.trim().isEmpty()) {
            symbol = scrip.getScripRefKey();
        }
        if (symbol == null || symbol.trim().isEmpty()) {
            symbol = scrip.getInstrumentName();
        }

        if (symbol == null) return null;
        symbol = symbol.trim();

        String exchange = scrip.getExchange() != null ? scrip.getExchange().toUpperCase() : "NSE";
        String segment = scrip.getSegment() != null ? scrip.getSegment().toUpperCase() : "";

        String instKey = null;
        if ("BFO".equalsIgnoreCase(exchange) || ("BSE".equalsIgnoreCase(exchange) && (segment.contains("FO") || segment.contains("DERIVATIVE")))) {
            instKey = "BSE_FO|" + symbol;
        } else if ("NFO".equalsIgnoreCase(exchange) || segment.contains("FO") || segment.contains("DERIVATIVE")) {
            instKey = "NSE_FO|" + symbol;
        } else if ("BSE".equalsIgnoreCase(exchange)) {
            String cleanSym = symbol.replaceAll("-(EQ|BE|BZ|SM)$", "");
            instKey = "BSE_EQ|" + cleanSym;
        } else {
            String cleanSym = symbol.replaceAll("-(EQ|BE|BZ|SM)$", "");
            instKey = "NSE_EQ|" + cleanSym;
        }

        if (cacheKey != null) instrumentKeyCache.put(cacheKey, instKey);
        return instKey;
    }

    private String extractUnderlyingName(String tradingSym, String refKey, String instName) {
        String str = (tradingSym + " " + refKey + " " + instName).toUpperCase();
        if (str.contains("BANKNIFTY") || str.contains("NIFTY BANK")) return "BANKNIFTY";
        if (str.contains("FINNIFTY") || str.contains("NIFTY FIN")) return "FINNIFTY";
        if (str.contains("MIDCPNIFTY") || str.contains("NIFTY MID")) return "MIDCPNIFTY";
        if (str.contains("NIFTYNXT50") || str.contains("NIFTY NEXT 50")) return "NIFTYNXT50";
        if (str.contains("NIFTY")) return "NIFTY";
        if (str.contains("SENSEX")) return "SENSEX";
        if (str.contains("BANKEX")) return "BANKEX";

        String first = tradingSym.split("[0-9]")[0];
        return first.isEmpty() ? null : first;
    }

    private Double extractStrikePrice(Scrip scrip) {
        if (scrip == null) return null;
        if (scrip.getStrikePrice() != null && scrip.getStrikePrice() > 0) {
            return scrip.getStrikePrice();
        }
        String refKey = scrip.getScripRefKey();
        if (refKey == null || refKey.trim().isEmpty()) {
            refKey = scrip.getTradingSymbol();
        }
        if (refKey != null) {
            Pattern pattern = Pattern.compile("(\\d+(?:\\.\\d+)?)(?:CE|PE)$", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(refKey.trim());
            if (matcher.find()) {
                try {
                    return Double.parseDouble(matcher.group(1));
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private final Map<String, CandleCacheEntry> candleCache = new ConcurrentHashMap<>();
    private record CandleCacheEntry(long timestamp, List<Map<String, Object>> candles) {}

    public List<Map<String, Object>> fetchHistoricalCandles(String instrumentKey, String timeframe) {
        if (instrumentKey == null || instrumentKey.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String tf = timeframe != null ? timeframe : "1m";
        String cacheKey = instrumentKey + "|" + tf;
        CandleCacheEntry cached = candleCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp()) < 10000) {
            return cached.candles();
        }

        String interval = switch (tf) {
            case "30m", "1h" -> "1minute";
            case "day", "1d" -> "day";
            default -> "1minute";
        };

        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(7);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        String toDateStr = today.format(fmt);
        String fromDateStr = fromDate.format(fmt);

        String encodedKey = encodeUriPathParam(instrumentKey);
        String urlWithDates = String.format("https://api.upstox.com/v2/historical-candle/%s/%s/%s/%s",
                encodedKey, interval, toDateStr, fromDateStr);
        String urlToDateOnly = String.format("https://api.upstox.com/v2/historical-candle/%s/%s/%s",
                encodedKey, interval, toDateStr);

        // Try 1: toDate/fromDate
        List<Map<String, Object>> bars = executeHistoricalCandleRequest(urlWithDates, instrumentKey);
        if (!bars.isEmpty()) {
            candleCache.put(cacheKey, new CandleCacheEntry(System.currentTimeMillis(), bars));
            return bars;
        }

        // Try 2: toDate only fallback
        bars = executeHistoricalCandleRequest(urlToDateOnly, instrumentKey);
        if (!bars.isEmpty()) {
            candleCache.put(cacheKey, new CandleCacheEntry(System.currentTimeMillis(), bars));
            return bars;
        }

        // Try 3: If instrumentKey was NSE_INDEX|Nifty 50, try fallback key if needed
        if (instrumentKey.contains("Nifty 50")) {
            String altUrl = String.format("https://api.upstox.com/v2/historical-candle/%s/%s/%s",
                    encodeUriPathParam("NSE_INDEX|Nifty50"), interval, toDateStr);
            bars = executeHistoricalCandleRequest(altUrl, "NSE_INDEX|Nifty50");
            if (!bars.isEmpty()) {
                candleCache.put(cacheKey, new CandleCacheEntry(System.currentTimeMillis(), bars));
            }
        }

        return bars;
    }

    public Map<String, Object> testHistoricalCandles(String key) {
        String testKey = (key == null || key.trim().isEmpty()) ? "NSE_INDEX|Nifty 50" : key.trim();
        String encodedKey = encodeUriPathParam(testKey);
        String url = String.format("https://api.upstox.com/v2/historical-candle/%s/1minute/%s/%s",
                encodedKey,
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                LocalDate.now().minusDays(7).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json");

            if (hasValidToken()) {
                reqBuilder.header("Authorization", "Bearer " + accessToken);
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.GET().build(), HttpResponse.BodyHandlers.ofString());
            return Map.of(
                    "testKey", testKey,
                    "url", url,
                    "statusCode", resp.statusCode(),
                    "hasToken", hasValidToken(),
                    "tokenSnippet", hasValidToken() ? accessToken.substring(0, Math.min(10, accessToken.length())) + "..." : "none",
                    "rawBody", resp.body()
            );
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    private String encodeUriPathParam(String param) {
        if (param == null) return "";
        return URLEncoder.encode(param, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private List<Map<String, Object>> executeHistoricalCandleRequest(String url, String instrumentKey) {
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json");

            if (hasValidToken()) {
                reqBuilder.header("Authorization", "Bearer " + accessToken);
            }

            log.info("[UpstoxService] Requesting Upstox historical candles: URL={}, Token Present={}", url, hasValidToken());
            HttpResponse<String> resp = httpClient.send(reqBuilder.GET().build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 429) {
                log.warn("[UpstoxService] Rate limit 429 hit for {}. Sleeping 600ms before retry...", instrumentKey);
                Thread.sleep(600);
                resp = httpClient.send(reqBuilder.GET().build(), HttpResponse.BodyHandlers.ofString());
            }

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

                    Collections.reverse(bars);
                    log.info("[UpstoxService] Successfully fetched {} historical candles for {}", bars.size(), instrumentKey);
                    return bars;
                } else {
                    log.warn("[UpstoxService] Upstox returned 200 but status/candles missing: {}", resp.body());
                }
            } else {
                log.warn("[UpstoxService] Historical candles HTTP {} for {}: {}", resp.statusCode(), instrumentKey, resp.body());
            }
        } catch (Exception e) {
            log.error("[UpstoxService] Exception executing candle request for {}: {}", instrumentKey, e.getMessage());
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
