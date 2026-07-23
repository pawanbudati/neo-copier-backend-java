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
    private String defaultBaseUrl;

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

        List<String> baseUrls = List.of(
                getBaseUrl(account),
                "https://gw-napi.kotaksecurities.com",
                "https://napi.kotaksecurities.com",
                "https://mis.kotaksecurities.com"
        );

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
        String baseUrl = getBaseUrl(account);
        return getRequest(baseUrl + "/limits/v1/margin?segment=ALL&exchange=ALL&product=ALL", account);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getPositions(Account account) {
        String baseUrl = getBaseUrl(account);
        Map<String, Object> res = getRequest(baseUrl + "/positions/v1/net", account);
        Object data = res.get("data");
        if (data instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getOrderBook(Account account) {
        String baseUrl = getBaseUrl(account);
        Map<String, Object> res = getRequest(baseUrl + "/orders/v1/orderBook", account);
        Object data = res.get("data");
        if (data instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    public Map<String, Object> placeOrder(Account account, Map<String, Object> payload) {
        String baseUrl = getBaseUrl(account);
        return postRequest(baseUrl + "/orders/v1/place", payload, account);
    }

    public Map<String, Object> modifyOrder(Account account, Map<String, Object> payload) {
        String baseUrl = getBaseUrl(account);
        return postRequest(baseUrl + "/orders/v1/modify", payload, account);
    }

    public Map<String, Object> cancelOrder(Account account, String orderId) {
        String baseUrl = getBaseUrl(account);
        Map<String, Object> payload = Map.of("order_id", orderId);
        return postRequest(baseUrl + "/orders/v1/cancel", payload, account);
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
        String baseUrl = getBaseUrl(account);
        try {
            Map<String, Object> res = getRequest(baseUrl + "/scrip_master/v1/file_paths", account);
            if (res != null && !res.isEmpty() && !res.containsKey("error")) {
                return res;
            }
        } catch (Exception e) {
            log.warn("[KotakApiClient] /scrip_master/v1/file_paths endpoint warning: {}", e.getMessage());
        }
        return getRequest(baseUrl + "/scrip_master/v1/", account);
    }

    private String getBaseUrl(Account account) {
        return account.getBaseUrl() != null && !account.getBaseUrl().isEmpty() ? account.getBaseUrl() : defaultBaseUrl;
    }

    private Map<String, Object> getRequest(String url, Account account) {
        return httpRequest("GET", url, null, account.getConsumerKey(), account.getSid(), account.getNeoToken());
    }

    private Map<String, Object> postRequest(String url, Object body, Account account) {
        return httpRequest("POST", url, body, account.getConsumerKey(), account.getSid(), account.getNeoToken());
    }

    private Map<String, Object> postRequest(String url, Object body, String consumerKey, String sid, String neoToken) {
        return httpRequest("POST", url, body, consumerKey, sid, neoToken);
    }

    private Map<String, Object> httpRequest(String method, String url, Object body, String consumerKey, String sid, String neoToken) {
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
            }
            if (neoToken != null && !neoToken.isEmpty()) {
                builder.header("Auth", neoToken);
            }
            builder.header("Content-Type", "application/json");

            String jsonBody = "{}";
            if ("POST".equalsIgnoreCase(method)) {
                jsonBody = body != null ? objectMapper.writeValueAsString(body) : "{}";
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                builder.GET();
            }

            log.info("[KotakApiClient] [OUTBOUND REQ] {} {} | Body: {}", method, url, jsonBody);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            log.info("[KotakApiClient] [OUTBOUND RES] {} {} | Status: {} | Body: {}", method, url, response.statusCode(), responseBody);

            if (responseBody != null && responseBody.trim().startsWith("{")) {
                return objectMapper.readValue(responseBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } else if (responseBody != null && responseBody.trim().startsWith("[")) {
                List<Object> list = objectMapper.readValue(responseBody, new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {});
                Map<String, Object> resMap = new HashMap<>();
                resMap.put("data", list);
                return resMap;
            } else {
                Map<String, Object> resMap = new HashMap<>();
                resMap.put("raw", responseBody);
                return resMap;
            }
        } catch (Exception e) {
            log.error("[KotakApiClient] Request failed: {} {} - {}", method, url, e.getMessage(), e);
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
