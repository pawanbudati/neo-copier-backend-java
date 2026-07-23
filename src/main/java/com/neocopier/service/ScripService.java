package com.neocopier.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocopier.client.KotakApiClient;
import com.neocopier.model.Account;
import com.neocopier.model.Scrip;
import com.neocopier.repository.ScripRepository;
import com.neocopier.util.ScripParser;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    private static final Map<String, List<String>> FALLBACK_SCRIP_URLS = Map.of(
            "nse_cm", List.of("https://file.kotaksecurities.com/scrip_master/nse_cm.csv", "https://lapi.kotaksecurities.com/nest_stream/script_master/nse_cm.csv"),
            "nse_fo", List.of("https://file.kotaksecurities.com/scrip_master/nse_fo.csv", "https://lapi.kotaksecurities.com/nest_stream/script_master/nse_fo.csv"),
            "bse_fo", List.of("https://file.kotaksecurities.com/scrip_master/bse_fo.csv", "https://lapi.kotaksecurities.com/nest_stream/script_master/bse_fo.csv"),
            "bse_cm", List.of("https://file.kotaksecurities.com/scrip_master/bse_cm.csv", "https://lapi.kotaksecurities.com/nest_stream/script_master/bse_cm.csv"),
            "mcx_fo", List.of("https://file.kotaksecurities.com/scrip_master/mcx_fo.csv", "https://lapi.kotaksecurities.com/nest_stream/script_master/mcx_fo.csv"),
            "cde_fo", List.of("https://file.kotaksecurities.com/scrip_master/cde_fo.csv", "https://lapi.kotaksecurities.com/nest_stream/script_master/cde_fo.csv")
    );

    private final ScripRepository scripRepository;
    private final KotakApiClient kotakApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    private final List<Scrip> inMemoryScripCache = new CopyOnWriteArrayList<>();
    private boolean isCacheLoaded = false;

    private final Map<String, List<Map<String, Object>>> ohlcHistory = new ConcurrentHashMap<>();

    public ScripService(ScripRepository scripRepository, KotakApiClient kotakApiClient) {
        this.scripRepository = scripRepository;
        this.kotakApiClient = kotakApiClient;
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
        List<Map<String, Object>> history = ohlcHistory.get(token);
        if (history == null || history.isEmpty()) return Collections.emptyList();

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

    public Map<String, Object> loadDailyIndexOptions(Account activeAccount) {
        log.info("[ScripMaster] Loading daily Nifty & Sensex index options (Filtering past expiries)...");
        LocalDate today = LocalDate.now();

        List<Scrip> niftyOptions = downloadAndFilterScrips("nse_fo", activeAccount, scrip -> {
            if (scrip == null) return false;
            String sym = scrip.getTradingSymbol() != null ? scrip.getTradingSymbol().toUpperCase() : "";
            String inst = scrip.getInstrumentName() != null ? scrip.getInstrumentName().toUpperCase() : "";
            String ref = scrip.getScripRefKey() != null ? scrip.getScripRefKey().toUpperCase() : "";
            
            boolean isNifty = sym.contains("NIFTY") || inst.contains("NIFTY") || ref.contains("NIFTY");
            boolean isOption = "CE".equalsIgnoreCase(scrip.getSegment()) || "PE".equalsIgnoreCase(scrip.getSegment()) ||
                               inst.contains("OPT") || sym.endsWith("CE") || sym.endsWith("PE");
            boolean isNotExpired = scrip.getExpiry() == null || !scrip.getExpiry().isBefore(today);

            return isNifty && isOption && isNotExpired;
        });

        List<Scrip> sensexOptions = downloadAndFilterScrips("bse_fo", activeAccount, scrip -> {
            if (scrip == null) return false;
            String sym = scrip.getTradingSymbol() != null ? scrip.getTradingSymbol().toUpperCase() : "";
            String inst = scrip.getInstrumentName() != null ? scrip.getInstrumentName().toUpperCase() : "";
            String ref = scrip.getScripRefKey() != null ? scrip.getScripRefKey().toUpperCase() : "";

            boolean isSensex = sym.contains("SENSEX") || sym.contains("BSX") || inst.contains("SENSEX") || ref.contains("SENSEX");
            boolean isOption = "CE".equalsIgnoreCase(scrip.getSegment()) || "PE".equalsIgnoreCase(scrip.getSegment()) ||
                               inst.contains("OPT") || sym.endsWith("CE") || sym.endsWith("PE");
            boolean isNotExpired = scrip.getExpiry() == null || !scrip.getExpiry().isBefore(today);

            return isSensex && isOption && isNotExpired;
        });

        // Clear ALL existing scrips from DB before saving fresh filtered index options
        scripRepository.deleteAll();

        List<Scrip> allNewScrips = new ArrayList<>();
        allNewScrips.addAll(niftyOptions);
        allNewScrips.addAll(sensexOptions);

        if (!allNewScrips.isEmpty()) {
            scripRepository.saveAll(allNewScrips);
        }

        // Clear RAM cache and reload freshly from DB
        int cachedCount = populateMemoryCache();
        log.info("[ScripMaster] Daily index options loaded. Nifty: {}, Sensex: {}. Total in RAM: {}",
                niftyOptions.size(), sensexOptions.size(), cachedCount);

        return Map.of(
                "success", true,
                "niftyCount", niftyOptions.size(),
                "sensexCount", sensexOptions.size(),
                "totalCount", cachedCount
        );
    }

    public List<Scrip> downloadAndFilterScrips(String categoryKey, Account activeAccount, java.util.function.Predicate<Scrip> filterPredicate) {
        String catKeyLower = categoryKey.toLowerCase();
        List<String> candidateUrls = new ArrayList<>();

        if (activeAccount != null) {
            try {
                Object masterPayload = kotakApiClient.getScripMaster(activeAccount);
                List<String> extracted = extractUrls(masterPayload);
                for (String u : extracted) {
                    if (u.toLowerCase().contains(catKeyLower)) {
                        candidateUrls.add(u);
                    }
                }
            } catch (Exception e) {
                log.warn("[ScripMaster] Failed to fetch scrip master from broker for {}: {}", categoryKey, e.getMessage());
            }
        }

        if (FALLBACK_SCRIP_URLS.containsKey(catKeyLower)) {
            for (String fallbackUrl : FALLBACK_SCRIP_URLS.get(catKeyLower)) {
                if (!candidateUrls.contains(fallbackUrl)) {
                    candidateUrls.add(fallbackUrl);
                }
            }
        }

        for (String targetUrl : candidateUrls) {
            try {
                log.info("[ScripMaster] Downloading {} scrip master from {}", categoryKey, targetUrl);
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(targetUrl)).GET().build();
                HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    log.warn("[ScripMaster] Non-200 HTTP status {} from {}", response.statusCode(), targetUrl);
                    continue;
                }

                List<Scrip> filtered = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                    String headerLine = reader.readLine();
                    if (headerLine != null) {
                        String[] headers = headerLine.replace("\ufeff", "").split(",");
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.trim().isEmpty()) continue;
                            String[] parts = line.split(",", -1);
                            Map<String, String> row = new HashMap<>();
                            for (int i = 0; i < Math.min(headers.length, parts.length); i++) {
                                String hKey = headers[i] != null ? headers[i].trim() : "";
                                String pVal = parts[i] != null ? parts[i].trim() : "";
                                if (!hKey.isEmpty()) {
                                    row.put(hKey, pVal);
                                }
                            }
                            try {
                                Scrip scrip = ScripParser.parseRow(row);
                                if (scrip != null && filterPredicate.test(scrip)) {
                                    filtered.add(scrip);
                                }
                            } catch (Exception parseEx) {
                                // Ignore single bad row
                            }
                        }
                    }
                }
                log.info("[ScripMaster] Successfully parsed and filtered {} scrips from {}", filtered.size(), targetUrl);
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            } catch (Exception e) {
                log.warn("[ScripMaster] Error downloading/parsing {}: {}", targetUrl, e.toString(), e);
            }
        }
        return Collections.emptyList();
    }

    public Map<String, Object> loadScripCategory(String categoryKey, Account activeAccount) {
        String catKeyLower = categoryKey != null ? categoryKey.toLowerCase() : "";
        List<String> candidateUrls = new ArrayList<>();

        if (activeAccount != null) {
            try {
                Object masterPayload = kotakApiClient.getScripMaster(activeAccount);
                List<String> extracted = extractUrls(masterPayload);
                for (String u : extracted) {
                    if (u.toLowerCase().contains(catKeyLower)) {
                        candidateUrls.add(u);
                    }
                }
            } catch (Exception e) {
                log.warn("[ScripMaster] Failed to fetch scrip master from broker for {}: {}", categoryKey, e.getMessage());
            }
        }

        if (FALLBACK_SCRIP_URLS.containsKey(catKeyLower)) {
            for (String fallbackUrl : FALLBACK_SCRIP_URLS.get(catKeyLower)) {
                if (!candidateUrls.contains(fallbackUrl)) {
                    candidateUrls.add(fallbackUrl);
                }
            }
        }

        if (candidateUrls.isEmpty()) {
            return Map.of("success", false, "error", "Scrip master URL for " + categoryKey + " not found from broker.");
        }

        for (String targetUrl : candidateUrls) {
            try {
                log.info("[ScripMaster] Downloading {} scrip master from {}", categoryKey, targetUrl);
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(targetUrl)).GET().build();
                HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    continue;
                }

                List<Scrip> parsedScrips = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                    String headerLine = reader.readLine();
                    if (headerLine != null) {
                        String[] headers = headerLine.replace("\ufeff", "").split(",");
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String[] parts = line.split(",");
                            Map<String, String> row = new HashMap<>();
                            for (int i = 0; i < Math.min(headers.length, parts.length); i++) {
                                row.put(headers[i].trim(), parts[i].trim());
                            }
                            Scrip scrip = ScripParser.parseRow(row);
                            if (scrip != null) {
                                parsedScrips.add(scrip);
                            }
                        }
                    }
                }

                if (!parsedScrips.isEmpty()) {
                    String exchange = parsedScrips.get(0).getExchange();
                    scripRepository.deleteByExchange(exchange);
                    scripRepository.saveAll(parsedScrips);
                    populateMemoryCache();
                    log.info("[ScripMaster] Successfully saved {} scrips for category {}", parsedScrips.size(), categoryKey);
                    return Map.of("success", true, "category", categoryKey, "count", parsedScrips.size(), "totalCount", scripRepository.count());
                }
            } catch (Exception e) {
                log.warn("[ScripMaster] Failed attempt downloading {} from {}: {}", categoryKey, targetUrl, e.getMessage());
            }
        }

        return Map.of("success", false, "error", "Failed to download scrip master for " + categoryKey + " from all available URLs.");
    }

    public Map<String, Object> clearScripCategory(String categoryKey) {
        String exchange = switch (categoryKey.toLowerCase()) {
            case "nse_fo" -> "NFO";
            case "bse_fo" -> "BFO";
            case "nse_cm" -> "NSE";
            case "bse_cm" -> "BSE";
            case "mcx_fo" -> "MCX";
            case "cde_fo" -> "CDE";
            default -> categoryKey.toUpperCase();
        };

        int cleared = scripRepository.deleteByExchange(exchange);
        populateMemoryCache();
        return Map.of("success", true, "category", categoryKey, "clearedCount", cleared, "totalCount", scripRepository.count());
    }

    public Map<String, Object> clearAllScrips() {
        scripRepository.deleteAll();
        populateMemoryCache();
        return Map.of("success", true, "clearedAll", true);
    }

    private List<String> extractUrls(Object payload) {
        List<String> urls = new ArrayList<>();
        if (payload instanceof String s) {
            if (s.contains(".csv") || s.startsWith("http")) urls.add(s);
        } else if (payload instanceof List<?> list) {
            for (Object item : list) urls.addAll(extractUrls(item));
        } else if (payload instanceof Map<?, ?> map) {
            for (Object value : map.values()) urls.addAll(extractUrls(value));
        }
        return urls;
    }
}
