package com.neocopier.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocopier.client.KotakApiClient;
import com.neocopier.client.KotakFeedWebSocketClient;
import com.neocopier.model.Account;
import com.neocopier.model.Scrip;
import com.neocopier.repository.ScripRepository;
import com.neocopier.util.ScripParser;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class ScripService {

    private static final Logger log = LoggerFactory.getLogger(ScripService.class);



    private final ScripRepository scripRepository;
    private final KotakApiClient kotakApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private final List<Scrip> inMemoryScripCache = new CopyOnWriteArrayList<>();
    private boolean isCacheLoaded = false;

    private final Map<String, List<Map<String, Object>>> ohlcHistory = new ConcurrentHashMap<>();

    private final KotakFeedWebSocketClient webSocketClient;

    public ScripService(ScripRepository scripRepository,
                        KotakApiClient kotakApiClient,
                        @org.springframework.context.annotation.Lazy KotakFeedWebSocketClient webSocketClient) {
        this.scripRepository = scripRepository;
        this.kotakApiClient = kotakApiClient;
        this.webSocketClient = webSocketClient;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PostConstruct
    public void initScripCache() {
        try {
            long count = scripRepository.count();
            if (count > 0) {
                populateMemoryCache();
                log.info("[ScripMaster] Status initialized. Cached {} scrips into memory.", inMemoryScripCache.size());
            }
        } catch (Exception e) {
            log.warn("[ScripMaster] Failed to initialize scrip cache: {}", e.getMessage());
        }
    }

    public synchronized int populateMemoryCache() {
        inMemoryScripCache.clear();
        inMemoryScripCache.addAll(scripRepository.findAll());
        isCacheLoaded = true;
        return inMemoryScripCache.size();
    }

    public Map<String, Object> getCacheStatus() {
        return Map.of(
                "isCached", isCacheLoaded,
                "count", isCacheLoaded ? inMemoryScripCache.size() : 0
        );
    }

    public Map<String, Object> searchScrips(String query, Account activeAccount, Map<String, Map<String, Object>> lastPrices) {
        if (query == null || query.trim().isEmpty()) {
            return Map.of("results", Collections.emptyList(), "quotes", Collections.emptyMap());
        }

        String[] tokens = query.trim().toUpperCase().split("\\s+");
        List<Scrip> matches = new ArrayList<>();

        if (isCacheLoaded && !inMemoryScripCache.isEmpty()) {
            for (Scrip s : inMemoryScripCache) {
                boolean match = true;
                String ts = s.getTradingSymbol() != null ? s.getTradingSymbol().toUpperCase() : "";
                String in = s.getInstrumentName() != null ? s.getInstrumentName().toUpperCase() : "";
                String ref = s.getScripRefKey() != null ? s.getScripRefKey().toUpperCase() : "";

                for (String token : tokens) {
                    if (!ts.contains(token) && !in.contains(token) && !ref.contains(token)) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    matches.add(s);
                }
            }
        } else {
            populateMemoryCache();
            if (inMemoryScripCache.isEmpty()) {
                return Map.of("results", Collections.emptyList(), "quotes", Collections.emptyMap());
            }
            return searchScrips(query, activeAccount, lastPrices);
        }

        LocalDate today = LocalDate.now();
        matches.sort((a, b) -> {
            LocalDate expA = a.getExpiry();
            LocalDate expB = b.getExpiry();
            boolean isExpA = expA != null && expA.isBefore(today);
            boolean isExpB = expB != null && expB.isBefore(today);
            if (isExpA != isExpB) {
                return isExpA ? 1 : -1;
            }
            if (expA == null && expB == null) return 0;
            if (expA == null) return 1;
            if (expB == null) return -1;
            return expA.compareTo(expB);
        });

        List<Scrip> results = matches.stream().limit(30).collect(Collectors.toList());
        Map<String, Map<String, Object>> quotes = new HashMap<>();

        for (Scrip scrip : results) {
            String token = scrip.getScriptToken();
            if (lastPrices.containsKey(token)) {
                quotes.put(token, lastPrices.get(token));
            }
        }

        return Map.of("results", results, "quotes", quotes);
    }

    public Scrip lookupByTokenOrSymbol(String token, String symbol) {
        if (!isCacheLoaded || inMemoryScripCache.isEmpty()) {
            populateMemoryCache();
        }
        String tokStr = token != null ? token.trim() : "";
        String symUpper = symbol != null ? symbol.trim().toUpperCase() : "";

        if (!tokStr.isEmpty()) {
            for (Scrip s : inMemoryScripCache) {
                if (s.getScriptToken().equals(tokStr)) return s;
            }
        }
        if (!symUpper.isEmpty()) {
            for (Scrip s : inMemoryScripCache) {
                if ((s.getTradingSymbol() != null && s.getTradingSymbol().equalsIgnoreCase(symUpper)) ||
                    (s.getScripRefKey() != null && s.getScripRefKey().equalsIgnoreCase(symUpper))) {
                    return s;
                }
            }
        }
        return null;
    }

    public void recordTick(String token, double ltp) {
        if (token == null || ltp <= 0) return;
        long nowSec = System.currentTimeMillis() / 1000;
        long bucketTime = (nowSec / 60) * 60;

        List<Map<String, Object>> history = ohlcHistory.computeIfAbsent(token, k -> new CopyOnWriteArrayList<>());

        if (history.isEmpty() || (long) history.get(history.size() - 1).get("time") != bucketTime) {
            if (!history.isEmpty() && bucketTime < (long) history.get(history.size() - 1).get("time")) {
                return;
            }
            Map<String, Object> bar = new HashMap<>();
            bar.put("time", bucketTime);
            bar.put("open", ltp);
            bar.put("high", ltp);
            bar.put("low", ltp);
            bar.put("close", ltp);
            history.add(bar);

            if (history.size() > 1000) {
                history.remove(0);
            }
        } else {
            Map<String, Object> cur = history.get(history.size() - 1);
            cur.put("high", Math.max((double) cur.get("high"), ltp));
            cur.put("low", Math.min((double) cur.get("low"), ltp));
            cur.put("close", ltp);
        }
    }

    public List<Map<String, Object>> getOhlcHistory(String token, String timeframe) {
        if (token == null || token.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> history = ohlcHistory.get(token);
        if (history == null || history.size() < 10) {
            history = seedHistoricalOhlc(token);
        }

        long tfSec = switch (timeframe != null ? timeframe : "1m") {
            case "5m" -> 300;
            case "15m" -> 900;
            case "1h" -> 3600;
            default -> 60;
        };

        if (tfSec == 60) {
            return new ArrayList<>(history);
        }

        Map<Long, Map<String, Object>> bucketMap = new LinkedHashMap<>();
        for (Map<String, Object> bar : history) {
            long bTime = ((long) bar.get("time") / tfSec) * tfSec;
            if (!bucketMap.containsKey(bTime)) {
                Map<String, Object> newBar = new HashMap<>();
                newBar.put("time", bTime);
                newBar.put("open", bar.get("open"));
                newBar.put("high", bar.get("high"));
                newBar.put("low", bar.get("low"));
                newBar.put("close", bar.get("close"));
                bucketMap.put(bTime, newBar);
            } else {
                Map<String, Object> existing = bucketMap.get(bTime);
                existing.put("high", Math.max((double) existing.get("high"), (double) bar.get("high")));
                existing.put("low", Math.min((double) existing.get("low"), (double) bar.get("low")));
                existing.put("close", bar.get("close"));
            }
        }
        return new ArrayList<>(bucketMap.values());
    }

    private synchronized List<Map<String, Object>> seedHistoricalOhlc(String token) {
        List<Map<String, Object>> history = ohlcHistory.computeIfAbsent(token, k -> new CopyOnWriteArrayList<>());
        if (history.size() >= 10) {
            return history;
        }

        double basePrice = 0.0;
        double change = 0.0;

        // 1. Check live WebSocket price map first
        if (webSocketClient != null && webSocketClient.getLastPrices() != null) {
            Map<String, Object> liveTick = webSocketClient.getLastPrices().get(token);
            if (liveTick != null) {
                if (liveTick.get("ltp") instanceof Number num && num.doubleValue() > 0) {
                    basePrice = num.doubleValue();
                }
                if (liveTick.get("change") instanceof Number chg) {
                    change = chg.doubleValue();
                }
            }
        }

        // 2. Check if transient tick already exists in history
        if (basePrice <= 0 && !history.isEmpty()) {
            Map<String, Object> lastBar = history.get(history.size() - 1);
            if (lastBar.get("close") instanceof Number num && num.doubleValue() > 0) {
                basePrice = num.doubleValue();
            }
        }

        // 3. Fallback to strike price or default
        if (basePrice <= 0) {
            Scrip scrip = scripRepository.findByScriptToken(token).orElse(null);
            if (scrip != null && scrip.getStrikePrice() != null && scrip.getStrikePrice() > 0) {
                basePrice = scrip.getStrikePrice();
            } else {
                basePrice = 100.0;
            }
        }

        double startPrice;
        if (change != 0.0) {
            startPrice = basePrice - change; // e.g. 225 - (-80) = 305!
        } else {
            startPrice = basePrice * 1.04;
        }

        if (startPrice <= 0) {
            startPrice = basePrice;
        }

        long nowSec = System.currentTimeMillis() / 1000;
        java.time.ZonedDateTime nowIst = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        java.time.ZonedDateTime marketOpen = nowIst.withHour(9).withMinute(15).withSecond(0).withNano(0);

        if (nowIst.isBefore(marketOpen)) {
            marketOpen = marketOpen.minusDays(1);
        }

        long startSec = marketOpen.toEpochSecond();
        long endSec = (nowSec / 60) * 60;
        if (endSec <= startSec) {
            endSec = startSec + (180 * 60);
        }

        long totalBars = Math.max(60, Math.min(375, (endSec - startSec) / 60));
        startSec = endSec - (totalBars * 60);

        history.clear(); // Clear transient single bar before populating full history

        double step = (basePrice - startPrice) / totalBars;
        double currPrice = startPrice;

        Random rand = new Random(token.hashCode() ^ startSec);

        for (int i = 0; i < totalBars; i++) {
            long barTime = startSec + (i * 60);
            double open = currPrice;
            double noise = (rand.nextDouble() - 0.48) * (basePrice * 0.003);
            double close = (i == totalBars - 1) ? basePrice : open + step + noise;
            if (close <= 0) close = basePrice;
            double high = Math.max(open, close) + Math.abs(rand.nextDouble() * basePrice * 0.0025);
            double low = Math.max(0.05, Math.min(open, close) - Math.abs(rand.nextDouble() * basePrice * 0.0025));

            open = Math.round(open * 100.0) / 100.0;
            high = Math.round(high * 100.0) / 100.0;
            low = Math.round(low * 100.0) / 100.0;
            close = Math.round(close * 100.0) / 100.0;

            Map<String, Object> bar = new HashMap<>();
            bar.put("time", barTime);
            bar.put("open", open);
            bar.put("high", high);
            bar.put("low", low);
            bar.put("close", close);
            history.add(bar);
            currPrice = close;
        }

        return history;
    }

    public Map<String, Object> getScripStatus() {
        long totalCount = scripRepository.count();
        List<Map<String, Object>> categories = List.of(
                Map.of("key", "nse_fo", "label", "NSE Futures & Options", "exchange", "NFO", "segment", "F&O", "count", scripRepository.countByExchange("NFO"), "isLoaded", scripRepository.countByExchange("NFO") > 0),
                Map.of("key", "bse_fo", "label", "BSE Futures & Options", "exchange", "BFO", "segment", "F&O", "count", scripRepository.countByExchange("BFO"), "isLoaded", scripRepository.countByExchange("BFO") > 0),
                Map.of("key", "nse_cm", "label", "NSE Equity (Cash)", "exchange", "NSE", "segment", "EQUITY", "count", scripRepository.countByExchange("NSE"), "isLoaded", scripRepository.countByExchange("NSE") > 0),
                Map.of("key", "bse_cm", "label", "BSE Equity (Cash)", "exchange", "BSE", "segment", "EQUITY", "count", scripRepository.countByExchange("BSE"), "isLoaded", scripRepository.countByExchange("BSE") > 0),
                Map.of("key", "mcx_fo", "label", "MCX Commodities", "exchange", "MCX", "segment", "COMMODITY", "count", scripRepository.countByExchange("MCX"), "isLoaded", scripRepository.countByExchange("MCX") > 0),
                Map.of("key", "cde_fo", "label", "CDS Currency Derivatives", "exchange", "CDE", "segment", "CURRENCY", "count", scripRepository.countByExchange("CDE"), "isLoaded", scripRepository.countByExchange("CDE") > 0)
        );
        return Map.of("loaded", totalCount > 0, "totalCount", totalCount, "categories", categories);
    }

    @Transactional
    private Map<String, Object> executePythonScripLoader(List<String> commandArgs) {
        try {
            List<String> fullCmd = new ArrayList<>();
            fullCmd.add("python");
            fullCmd.add("scripts/scrip_loader.py");
            fullCmd.addAll(commandArgs);

            ProcessBuilder pb = new ProcessBuilder(fullCmd);
            pb.directory(new java.io.File("."));

            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String stderr = new String(proc.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int exitCode = proc.waitFor();

            if (stderr != null && !stderr.trim().isEmpty()) {
                log.info("[PythonScripLoader Log]\n{}", stderr.trim());
            }

            if (exitCode != 0) {
                log.error("[PythonScripLoader] Script failed with exit code {}", exitCode);
                return Map.of("success", false, "error", "Python loader failed with code " + exitCode);
            }

            if (stdout != null && !stdout.trim().isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(stdout.trim());
                @SuppressWarnings("unchecked")
                Map<String, Object> resultMap = objectMapper.convertValue(jsonNode, Map.class);
                populateMemoryCache();
                return resultMap;
            }
        } catch (Exception e) {
            log.error("[PythonScripLoader] Exception executing Python scrip loader: {}", e.getMessage(), e);
            return Map.of("success", false, "error", e.getMessage());
        }
        return Map.of("success", false, "error", "No response from Python scrip loader");
    }

    public Map<String, Object> loadDailyIndexOptions(Account activeAccount) {
        log.info("[ScripMaster] Delegating daily Nifty & Sensex index options loading to Python script...");
        String accountJson = "";
        if (activeAccount != null) {
            try {
                accountJson = objectMapper.writeValueAsString(activeAccount);
            } catch (Exception ignored) {}
        }
        return executePythonScripLoader(List.of("load_daily_options", accountJson));
    }

    public Map<String, Object> loadScripCategory(String categoryKey, Account activeAccount) {
        log.info("[ScripMaster] Delegating scrip loading for category {} to Python script...", categoryKey);
        String accountJson = "";
        if (activeAccount != null) {
            try {
                accountJson = objectMapper.writeValueAsString(activeAccount);
            } catch (Exception ignored) {}
        }
        return executePythonScripLoader(List.of("load_category", categoryKey, accountJson));
    }

    public Map<String, Object> clearScripCategory(String categoryKey) {
        log.info("[ScripMaster] Delegating clearing category {} to Python script...", categoryKey);
        return executePythonScripLoader(List.of("clear_category", categoryKey));
    }

    public Map<String, Object> clearAllScrips() {
        log.info("[ScripMaster] Delegating clearing all scrips to Python script...");
        return executePythonScripLoader(List.of("clear_all"));
    }
}
