package com.neocopier.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocopier.model.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Component
public class KotakApiClient {

    private static final Logger log = LoggerFactory.getLogger(KotakApiClient.class);

    @Value("${kotak.api.base:https://mis.kotaksecurities.com}")
    private String defaultBaseUrl = "https://mis.kotaksecurities.com";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public KotakApiClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public Map<String, Object> authenticate(Account account, String manualOtp) {
        String totpSecret = account.getTotpSecret();
        String mpin = account.getMpin();

        String totpCode = null;
        if (manualOtp != null && !manualOtp.trim().isEmpty()) {
            totpCode = manualOtp.trim();
        } else if (totpSecret != null && !totpSecret.trim().isEmpty()) {
            totpCode = com.neocopier.util.TotpUtils.generateTotp(totpSecret);
        }

        if (totpCode == null || totpCode.trim().isEmpty()) {
            throw new RuntimeException("TOTP secret or manual OTP code is required for account authentication.");
        }

        String formattedMobile = normalizeMobileNumber(account.getMobileNumber());
        String ucc = account.getUcc() != null ? account.getUcc().trim() : "";

        Map<String, Object> payload1 = Map.of(
                "mobileNumber", formattedMobile,
                "ucc", ucc,
                "totp", totpCode
        );

        // Python SDK: NeoAPI uses baseUrl or NEO_API_BASE (mis.kotaksecurities.com)
        List<String> baseUrls = getCandidateBaseUrls(account);

        List<String> step1Paths = List.of(
                "/login/1.0/tradeApiLogin",
                "/login/1.0/login/v6/totp/login",
                "/oauth2/token"
        );

        Map<String, Object> step1Res = null;
        String token = null;
        String sid = null;
        String workingBaseUrl = getBaseUrl(account);

        for (String base : baseUrls) {
            if (base == null || base.trim().isEmpty()) continue;
            String cleanBase = base.replaceAll("/+$", "");
            for (String path : step1Paths) {
                String fullUrl = cleanBase + path;
                log.info("[KotakApiClient] Trying Step 1 TOTP URL: {}", fullUrl);
                Map<String, Object> res = postRequest(fullUrl, payload1, account.getConsumerKey(), null, null);
                token = extractToken(res);
                sid = extractSid(res);
                if (token != null && sid != null) {
                    step1Res = res;
                    workingBaseUrl = cleanBase;
                    break;
                } else {
                    step1Res = res;
                }
            }
            if (token != null && sid != null) break;
        }

        if (token == null || sid == null) {
            log.warn("[KotakApiClient] Step 1 TOTP authentication failed: {}", step1Res);
            String err = extractErrorMessage(step1Res, "TOTP authentication failed. Check Mobile Number (+91 format), UCC, and 6-digit TOTP code.");
            throw new RuntimeException(err);
        }

        // Step 2: MPIN validate
        Map<String, Object> payload2 = Map.of(
                "mpin", mpin != null ? mpin.trim() : ""
        );

        List<String> step2Paths = List.of(
                "/login/1.0/tradeApiValidate",
                "/login/1.0/login/v6/totp/validate",
                "/oauth2/token/mpin"
        );

        Map<String, Object> step2Res = null;
        String neoToken = null;
        String hsServerId = null;

        for (String path : step2Paths) {
            String fullUrl = workingBaseUrl + path;
            log.info("[KotakApiClient] Trying Step 2 MPIN URL: {}", fullUrl);
            Map<String, Object> res = postRequest(fullUrl, payload2, account.getConsumerKey(), sid, token);
            neoToken = extractToken(res);
            hsServerId = (String) extractFromDataOrMap(res, "hsServerId", "hs_server_id");
            if (neoToken != null) {
                step2Res = res;
                break;
            } else {
                step2Res = res;
            }
        }

        if (neoToken == null) {
            log.warn("[KotakApiClient] Step 2 MPIN authentication failed: {}", step2Res);
            String err = extractErrorMessage(step2Res, "MPIN authentication failed. Check 4-digit MPIN.");
            throw new RuntimeException(err);
        }

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("accessToken", token);
        res.put("sid", sid);
        res.put("neoToken", neoToken);
        res.put("rid", extractFromDataOrMap(step2Res, "rid", "edit_rid"));
        res.put("hsServerId", hsServerId);
        res.put("dataCenter", extractFromDataOrMap(step2Res, "dataCenter", "data_center"));
        res.put("baseUrl", workingBaseUrl);
        return res;
    }

    public Map<String, Object> getLimits(Account account) {
        // Kotak Neo SDK LimitsAPI: POST /quick/user/limits or /Orders/2.0/quick/user/limits
        Map<String, Object> body = Map.of("seg", "ALL", "exch", "ALL", "prod", "ALL");
        for (String base : getCandidateBaseUrls(account)) {
            List<String> postPaths = List.of("/quick/user/limits", "/Orders/2.0/quick/user/limits");
            for (String path : postPaths) {
                try {
                    Map<String, Object> res = postFormRequest(base + path, body, account);
                    if (isValidResponse(res)) return res;
                } catch (Exception ignored) {}
                try {
                    Map<String, Object> res = postRequest(base + path, body, account);
                    if (isValidResponse(res)) return res;
                } catch (Exception ignored) {}
            }
            try {
                Map<String, Object> res = getRequest(base + "/limits/v1/margin?segment=ALL&exchange=ALL&product=ALL", account);
                if (isValidResponse(res)) return res;
            } catch (Exception ignored) {}
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getPositions(Account account) {
        // Kotak Neo SDK PositionsAPI: GET /quick/user/positions or /Orders/2.0/quick/user/positions
        List<String> paths = List.of("/quick/user/positions", "/Orders/2.0/quick/user/positions", "/positions/v1/net");
        for (String base : getCandidateBaseUrls(account)) {
            for (String path : paths) {
                try {
                    Map<String, Object> res = getRequest(base + path, account);
                    Object data = res.get("data");
                    if (data instanceof List<?> list) {
                        return (List<Map<String, Object>>) list;
                    }
                } catch (Exception ignored) {}
            }
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getOrderBook(Account account) {
        // Kotak Neo SDK OrderReportAPI: GET /quick/user/orders or /Orders/2.0/quick/user/orders
        List<String> paths = List.of("/quick/user/orders", "/Orders/2.0/quick/user/orders", "/orders/v1/orderBook");
        for (String base : getCandidateBaseUrls(account)) {
            for (String path : paths) {
                try {
                    Map<String, Object> res = getRequest(base + path, account);
                    Object data = res.get("data");
                    if (data instanceof List<?> list) {
                        return (List<Map<String, Object>>) list;
                    }
                } catch (Exception ignored) {}
            }
        }
        return Collections.emptyList();
    }

    public Map<String, Object> placeOrder(Account account, Map<String, Object> payload) {
        List<String> paths = List.of(
                "/quick/order/rule/ms/place",
                "/Orders/2.0/quick/order/rule/ms/place",
                "/quick/order/place"
        );
        log.info("[KotakApiClient] placeOrder payload: {}", payload);
        for (String base : getCandidateBaseUrls(account)) {
            // Try form-encoded jData format for /quick/ endpoints (Kotak Neo SDK format)
            for (String path : paths) {
                try {
                    Map<String, Object> res = postFormRequest(base + path, payload, account);
                    log.info("[KotakApiClient] placeOrder response from {}{}: {}", base, path, res);
                    if (isValidResponse(res)) {
                        log.info("[KotakApiClient] placeOrder SUCCESS via {}{}", base, path);
                        return res;
                    }
                } catch (Exception e) {
                    log.warn("[KotakApiClient] placeOrder failed via {}{}: {}", base, path, e.getMessage());
                }
            }
            // Fallback: try v1 REST API with JSON
            try {
                Map<String, Object> res = postRequest(base + "/orders/v1/place", payload, account);
                log.info("[KotakApiClient] placeOrder response from {}/orders/v1/place: {}", base, res);
                if (isValidResponse(res)) {
                    log.info("[KotakApiClient] placeOrder SUCCESS via {}/orders/v1/place", base);
                    return res;
                }
            } catch (Exception e) {
                log.warn("[KotakApiClient] placeOrder failed via {}/orders/v1/place: {}", base, e.getMessage());
            }
        }
        String baseUrl = getBaseUrl(account);
        return postFormRequest(baseUrl + "/quick/order/rule/ms/place", payload, account);
    }

    public Map<String, Object> modifyOrder(Account account, Map<String, Object> payload) {
        List<String> paths = List.of(
                "/quick/order/rule/ms/modify",
                "/Orders/2.0/quick/order/rule/ms/modify",
                "/quick/order/modify"
        );
        for (String base : getCandidateBaseUrls(account)) {
            for (String path : paths) {
                try {
                    Map<String, Object> res = postFormRequest(base + path, payload, account);
                    if (isValidResponse(res)) {
                        log.info("[KotakApiClient] modifyOrder SUCCESS via {}{}", base, path);
                        return res;
                    }
                } catch (Exception ignored) {}
            }
            try {
                Map<String, Object> res = postRequest(base + "/orders/v1/modify", payload, account);
                if (isValidResponse(res)) {
                    log.info("[KotakApiClient] modifyOrder SUCCESS via {}/orders/v1/modify", base);
                    return res;
                }
            } catch (Exception ignored) {}
        }
        String baseUrl = getBaseUrl(account);
        return postFormRequest(baseUrl + "/quick/order/rule/ms/modify", payload, account);
    }

    public Map<String, Object> cancelOrder(Account account, String orderId) {
        List<String> paths = List.of(
                "/quick/order/rule/ms/cancel",
                "/Orders/2.0/quick/order/rule/ms/cancel",
                "/quick/order/cancel"
        );
        Map<String, Object> payload = Map.of("order_id", orderId, "on", orderId, "no", orderId);
        for (String base : getCandidateBaseUrls(account)) {
            for (String path : paths) {
                try {
                    Map<String, Object> res = postFormRequest(base + path, payload, account);
                    if (isValidResponse(res)) {
                        log.info("[KotakApiClient] cancelOrder SUCCESS via {}{}", base, path);
                        return res;
                    }
                } catch (Exception ignored) {}
            }
            try {
                Map<String, Object> res = postRequest(base + "/orders/v1/cancel", payload, account);
                if (isValidResponse(res)) {
                    log.info("[KotakApiClient] cancelOrder SUCCESS via {}/orders/v1/cancel", base);
                    return res;
                }
            } catch (Exception ignored) {}
        }
        String baseUrl = getBaseUrl(account);
        return postFormRequest(baseUrl + "/quick/order/rule/ms/cancel", payload, account);
    }

    public Map<String, Object> marginRequired(Account account, Map<String, Object> payload) {
        String baseUrl = getBaseUrl(account);
        return postRequest(baseUrl + "/margin/v1/required", payload, account);
    }

    public Object getQuotes(Account account, List<Map<String, String>> instruments) {
        String baseUrl = getBaseUrl(account);
        Map<String, Object> payload = Map.of("instrument_tokens", instruments);
        return postRequest(baseUrl + "/quotes/v1/quotes", payload, account);
    }

    public Object getScripMaster(Account account) {
        // Kotak Neo API endpoints for scrip master file paths:
        List<String> paths = List.of(
                "/script-details/1.0/masterscrip/file-paths",
                "/Files/1.0/masterscrip/v2/file-paths",
                "/scrip_master/v1/file_paths"
        );
        for (String base : getCandidateBaseUrls(account)) {
            for (String path : paths) {
                try {
                    Map<String, Object> res = getRequest(base + path, account);
                    if (isValidResponse(res)) return res;
                } catch (Exception ignored) {}
            }
        }
        return Collections.emptyMap();
    }

    /**
     * Returns candidate base URLs matching Python SDK behavior:
     * 1. Account's baseUrl from login (e.g. https://e21.kotaksecurities.com)
     * 2. Default NEO_API_BASE (https://mis.kotaksecurities.com)
     */
    private List<String> getCandidateBaseUrls(Account account) {
        String accountBase = account.getBaseUrl();
        if (accountBase != null && !accountBase.trim().isEmpty()) {
            return List.of(accountBase.trim().replaceAll("/+$", ""));
        }
        String defBase = defaultBaseUrl.replaceAll("/+$", "");
        return List.of(defBase);
    }

    private boolean isValidResponse(Map<String, Object> res) {
        if (res == null || res.isEmpty() || res.containsKey("error")) return false;
        String raw = (String) res.get("raw");
        if (raw != null && (raw.contains("404") || raw.contains("Not Found") || raw.contains("Cannot POST") || raw.contains("Cannot GET"))) return false;
        String stat = (String) res.get("stat");
        if ("NotOk".equalsIgnoreCase(stat)) {
            String errMsg = (String) res.get("errMsg");
            if (errMsg != null && (errMsg.contains("404") || errMsg.contains("Invalid URL") || errMsg.contains("Not Found"))) return false;
        }
        return true;
    }

    private String getBaseUrl(Account account) {
        return account.getBaseUrl() != null && !account.getBaseUrl().isEmpty() ? account.getBaseUrl() : defaultBaseUrl;
    }

    private String getAuthToken(Account account) {
        if (account != null && account.getAccessToken() != null && !account.getAccessToken().trim().isEmpty()) {
            return account.getAccessToken().trim();
        }
        return account != null ? account.getConsumerKey() : null;
    }

    private Map<String, Object> getRequest(String url, Account account) {
        return httpRequest("GET", url, null, account, false, false);
    }

    private Map<String, Object> postRequest(String url, Object body, Account account) {
        return httpRequest("POST", url, body, account, false, false);
    }

    private Map<String, Object> postRequest(String url, Object body, String consumerKey, String sid, String neoToken) {
        return httpRequestDirect("POST", url, body, consumerKey, sid, neoToken, false);
    }

    /**
     * POST with application/x-www-form-urlencoded and jData=<json> format.
     * This matches the Kotak Neo Python SDK format for /quick/ endpoints.
     */
    private Map<String, Object> postFormRequest(String url, Object body, Account account) {
        return httpRequest("POST", url, body, account, true, false);
    }

    private Map<String, Object> httpRequest(String method, String url, Object body, Account account, boolean useFormEncoding, boolean isRetry) {
        String token = getAuthToken(account);
        String sid = account != null ? account.getSid() : null;
        String neoToken = account != null ? account.getNeoToken() : null;

        Map<String, Object> res = httpRequestDirect(method, url, body, token, sid, neoToken, useFormEncoding);

        if (!isRetry && account != null && isSessionInvalidResponse(res)) {
            if (attemptAutoRelogin(account)) {
                String freshToken = getAuthToken(account);
                return httpRequestDirect(method, url, body, freshToken, account.getSid(), account.getNeoToken(), useFormEncoding);
            }
        }
        return res;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.neocopier.repository.AccountRepository accountRepository;

    private final Map<String, Long> lastAutoReloginMap = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean isSessionInvalidResponse(Map<String, Object> res) {
        if (res == null) return false;
        String raw = (String) res.get("raw");
        if (raw != null && (raw.contains("invalid session token") || raw.contains("100022"))) return true;
        String errMsg = (String) res.get("errMsg");
        if (errMsg != null && errMsg.contains("invalid session token")) return true;
        String error = (String) res.get("error");
        if (error != null && error.contains("invalid session token")) return true;
        return false;
    }

    private boolean attemptAutoRelogin(Account account) {
        if (account == null || account.getId() == null) return false;
        String totpSecret = account.getTotpSecret();
        String mpin = account.getMpin();
        if (totpSecret == null || totpSecret.trim().isEmpty() || mpin == null || mpin.trim().isEmpty()) {
            return false;
        }

        // Throttle auto-relogin to at most once every 60 seconds per account
        long now = System.currentTimeMillis();
        Long lastTime = lastAutoReloginMap.get(account.getId());
        if (lastTime != null && (now - lastTime) < 60_000) {
            return false;
        }
        lastAutoReloginMap.put(account.getId(), now);

        try {
            log.info("[KotakApiClient] Session expired/invalid for {}. Auto-reauthenticating...", account.getNickname());
            Map<String, Object> auth = authenticate(account, null);
            if (Boolean.TRUE.equals(auth.get("success"))) {
                account.setAccessToken((String) auth.get("accessToken"));
                account.setSid((String) auth.get("sid"));
                account.setNeoToken((String) auth.get("neoToken"));
                if (auth.get("baseUrl") != null) account.setBaseUrl((String) auth.get("baseUrl"));
                account.setStatus("active");
                account.setLastLogin(java.time.LocalDateTime.now().toString());
                account.setErrorMessage(null);

                if (accountRepository != null) {
                    accountRepository.save(account);
                    log.info("[KotakApiClient] Auto-reauthentication SUCCESS for {} (saved new tokens to DB)", account.getNickname());
                } else {
                    log.info("[KotakApiClient] Auto-reauthentication SUCCESS for {}", account.getNickname());
                }
                return true;
            }
        } catch (Exception e) {
            log.warn("[KotakApiClient] Auto-reauthentication failed for {}: {}", account.getNickname(), e.getMessage());
        }
        return false;
    }

    private Map<String, Object> httpRequestDirect(String method, String url, Object body, String consumerKey, String sid, String neoToken, boolean useFormEncoding) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15));

            if (consumerKey != null && !consumerKey.isEmpty()) {
                builder.header("Authorization", consumerKey);
            }
            builder.header("neo-fin-key", "neotradeapi");

            if (sid != null && !sid.isEmpty()) {
                builder.header("sid", sid);
                builder.header("Sid", sid);
            }
            if (neoToken != null && !neoToken.isEmpty()) {
                builder.header("Auth", neoToken);
            }

            String requestBody = "{}";
            if ("POST".equalsIgnoreCase(method)) {
                String jsonStr = body != null ? objectMapper.writeValueAsString(body) : "{}";
                if (useFormEncoding) {
                    builder.header("Content-Type", "application/x-www-form-urlencoded");
                    requestBody = "jData=" + java.net.URLEncoder.encode(jsonStr, java.nio.charset.StandardCharsets.UTF_8);
                } else {
                    builder.header("Content-Type", "application/json");
                    requestBody = jsonStr;
                }
                builder.POST(HttpRequest.BodyPublishers.ofString(requestBody));
            } else {
                builder.header("Content-Type", "application/json");
                builder.GET();
            }

            log.debug("[KotakApiClient] [OUTBOUND REQ] {} {} | Body: {}", method, url, requestBody);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            if (response.statusCode() >= 400) {
                log.debug("[KotakApiClient] [OUTBOUND ERR] {} {} | Status: {} | Body: {}", method, url, response.statusCode(), responseBody);
            } else {
                log.debug("[KotakApiClient] [OUTBOUND RES] {} {} | Status: {} | Body: {}", method, url, response.statusCode(), responseBody);
            }

            if (responseBody == null || responseBody.trim().isEmpty()) {
                Map<String, Object> resMap = new HashMap<>();
                resMap.put("raw", "");
                return resMap;
            }

            String trimmed = responseBody.trim();
            if (trimmed.startsWith("{")) {
                try {
                    return objectMapper.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception parseEx) {
                    log.warn("[KotakApiClient] Non-JSON or malformed object response from {} {}: {}", method, url, parseEx.getMessage());
                    Map<String, Object> resMap = new HashMap<>();
                    resMap.put("raw", responseBody);
                    resMap.put("error", "Invalid JSON response: " + parseEx.getMessage());
                    return resMap;
                }
            } else if (trimmed.startsWith("[")) {
                try {
                    List<Object> list = objectMapper.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
                    Map<String, Object> resMap = new HashMap<>();
                    resMap.put("data", list);
                    return resMap;
                } catch (Exception parseEx) {
                    log.warn("[KotakApiClient] Non-JSON or malformed array response from {} {}: {}", method, url, parseEx.getMessage());
                    Map<String, Object> resMap = new HashMap<>();
                    resMap.put("raw", responseBody);
                    resMap.put("error", "Invalid JSON array response: " + parseEx.getMessage());
                    return resMap;
                }
            } else {
                Map<String, Object> resMap = new HashMap<>();
                resMap.put("raw", responseBody);
                if (response.statusCode() >= 400) {
                    resMap.put("error", "HTTP " + response.statusCode() + ": " + responseBody);
                }
                return resMap;
            }
        } catch (Exception e) {
            log.warn("[KotakApiClient] Request failed: {} {} - {}", method, url, e.getMessage());
            Map<String, Object> errMap = new HashMap<>();
            errMap.put("error", e.getMessage());
            return errMap;
        }
    }

    private String normalizeMobileNumber(String mobileNumber) {
        if (mobileNumber == null) return "";
        String trimmed = mobileNumber.trim();
        if (trimmed.startsWith("+")) {
            return trimmed;
        }
        String digits = trimmed.replaceAll("\\D+", "");
        if (digits.length() == 10) {
            return "+91" + digits;
        }
        if (digits.length() == 12 && digits.startsWith("91")) {
            return "+" + digits;
        }
        return trimmed;
    }

    private String extractToken(Map<String, Object> res) {
        if (res == null) return null;
        if (res.get("token") instanceof String t && !t.isEmpty()) return t;
        if (res.get("data") instanceof Map<?, ?> dataMap) {
            if (dataMap.get("token") instanceof String t && !t.isEmpty()) return t;
        }
        return null;
    }

    private String extractSid(Map<String, Object> res) {
        if (res == null) return null;
        if (res.get("sid") instanceof String s && !s.isEmpty()) return s;
        if (res.get("data") instanceof Map<?, ?> dataMap) {
            if (dataMap.get("sid") instanceof String s && !s.isEmpty()) return s;
        }
        return null;
    }

    private Object extractFromDataOrMap(Map<String, Object> res, String... keys) {
        if (res == null) return null;
        for (String k : keys) {
            if (res.get(k) != null) return res.get(k);
        }
        if (res.get("data") instanceof Map<?, ?> dataMap) {
            for (String k : keys) {
                if (dataMap.get(k) != null) return dataMap.get(k);
            }
        }
        return null;
    }

    private String extractErrorMessage(Map<String, Object> resMap, String fallback) {
        if (resMap == null || resMap.isEmpty()) return fallback;
        if (resMap.get("message") instanceof String msg && !msg.isEmpty()) return msg;
        if (resMap.get("error") instanceof String err && !err.isEmpty()) return err;
        if (resMap.get("data") instanceof Map<?, ?> dataMap) {
            if (dataMap.get("message") instanceof String msg && !msg.isEmpty()) return msg;
            if (dataMap.get("error") instanceof String err && !err.isEmpty()) return err;
        }
        return fallback;
    }
}
