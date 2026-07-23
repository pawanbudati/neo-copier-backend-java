package com.neocopier.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neocopier.model.Account;
import com.neocopier.util.TotpUtils;
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
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static String normalizeMobileNumber(String mobile) {
        if (mobile == null) return "";
        String trimmed = mobile.trim();
        if (trimmed.startsWith("+")) return trimmed;
        String digits = trimmed.replaceAll("\\D+", "");
        if (digits.length() == 10) return "+91" + digits;
        if (digits.length() == 12 && digits.startsWith("91")) return "+" + digits;
        return trimmed;
    }

    public Map<String, Object> authenticate(Account account, String manualOtp) {
        String totpCode;
        if (TotpUtils.hasAutoTotpSecret(account.getTotpSecret())) {
            totpCode = TotpUtils.generateTotp(account.getTotpSecret());
        } else if (manualOtp != null && !manualOtp.trim().isEmpty()) {
            totpCode = manualOtp.trim();
        } else {
            throw new RuntimeException("Enter the current 6-digit TOTP from your authenticator app.");
        }

        if (account.getConsumerKey() == null || account.getConsumerKey().trim().isEmpty()) {
            throw new RuntimeException("Consumer Key is required.");
        }
        if (account.getUcc() == null || account.getUcc().trim().isEmpty()) {
            throw new RuntimeException("UCC (Unique Client Code) is required.");
        }
        if (account.getMpin() == null || account.getMpin().trim().isEmpty()) {
            throw new RuntimeException("MPIN is required for login.");
        }

        String baseUrl = account.getBaseUrl() != null && !account.getBaseUrl().isEmpty() ? account.getBaseUrl() : defaultBaseUrl;

        // Step 1: TOTP Login
        log.info("[Auth] Step 1: TOTP Login for {} via Kotak API...", account.getNickname());
        Map<String, Object> loginBody = Map.of(
                "mobilenumber", normalizeMobileNumber(account.getMobileNumber()),
                "ucc", account.getUcc(),
                "totp", totpCode
        );

        Map<String, Object> loginRes = postRequest(baseUrl + "/login/v2/totp/login", loginBody, account.getConsumerKey(), null, null);
        String sid = extractString(loginRes, List.of("sid", "Sid", "session_id"));
        String neoToken = extractString(loginRes, List.of("neoToken", "neo_token", "token", "Auth"));

        // Step 2: MPIN Validate
        log.info("[Auth] Step 2: MPIN Validate for {} via Kotak API...", account.getNickname());
        Map<String, Object> validateBody = Map.of("mpin", account.getMpin(), "OTP", account.getMpin());
        Map<String, Object> validateRes = postRequest(baseUrl + "/login/v2/totp/validate", validateBody, account.getConsumerKey(), sid, neoToken);

        if (sid == null || sid.isEmpty()) {
            sid = extractString(validateRes, List.of("sid", "Sid", "session_id"));
        }
        if (neoToken == null || neoToken.isEmpty()) {
            neoToken = extractString(validateRes, List.of("neoToken", "neo_token", "token", "Auth"));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("accessToken", account.getConsumerKey());
        result.put("sid", sid);
        result.put("neoToken", neoToken);
        result.put("rid", extractString(validateRes, List.of("rid")));
        result.put("hsServerId", extractString(validateRes, List.of("hsServerId", "hs_server_id")));
        result.put("dataCenter", extractString(validateRes, List.of("dataCenter", "data_center")));
        result.put("baseUrl", extractString(validateRes, List.of("baseUrl", "base_url")));
        return result;
    }

    public List<Map<String, Object>> getOrderBook(Account account) {
        String baseUrl = getBaseUrl(account);
        Map<String, Object> res = getRequest(baseUrl + "/orders/v1/order_report", account);
        return asList(res);
    }

    public List<Map<String, Object>> getPositions(Account account) {
        String baseUrl = getBaseUrl(account);
        Map<String, Object> res = getRequest(baseUrl + "/positions/v1/positions", account);
        return asList(res);
    }

    public Map<String, Object> getLimits(Account account) {
        String baseUrl = getBaseUrl(account);
        Map<String, Object> res = getRequest(baseUrl + "/limits/v1/margin?segment=ALL&exchange=ALL&product=ALL", account);
        if (res.get("data") instanceof List<?> list && !list.isEmpty()) {
            return (Map<String, Object>) list.get(0);
        }
        if (res.get("data") instanceof Map<?,?> map) {
            return (Map<String, Object>) map;
        }
        return res;
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
                    .header("Content-Type", "application/json")
                    .header("ipaddress", "127.0.0.1")
                    .header("appSource", "custom");

            if (consumerKey != null && !consumerKey.isEmpty()) {
                builder.header("ConsumerKey", consumerKey);
                builder.header("Authorization", "Bearer " + consumerKey);
            }
            if (sid != null && !sid.isEmpty()) {
                builder.header("sid", sid);
                builder.header("session_id", sid);
            }
            if (neoToken != null && !neoToken.isEmpty()) {
                builder.header("Auth", neoToken);
                builder.header("neo_token", neoToken);
                builder.header("token", neoToken);
            }

            if ("POST".equalsIgnoreCase(method)) {
                String jsonBody = body != null ? objectMapper.writeValueAsString(body) : "{}";
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                builder.GET();
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new RuntimeException("Session expired or unauthorized (HTTP " + response.statusCode() + ").");
            }

            if (responseBody == null || responseBody.trim().isEmpty()) {
                return Collections.emptyMap();
            }

            if (responseBody.trim().startsWith("[")) {
                List<Object> list = objectMapper.readValue(responseBody, new TypeReference<>() {});
                Map<String, Object> res = new HashMap<>();
                res.put("data", list);
                return res;
            }

            return objectMapper.readValue(responseBody, new TypeReference<>() {});

        } catch (Exception e) {
            log.error("[KotakApiClient] Request failed: {} {} - {}", method, url, e.getMessage());
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getMessage());
            err.put("stat", "NotOk");
            return err;
        }
    }

    private String extractString(Map<String, Object> data, List<String> keys) {
        if (data == null) return null;
        for (String key : keys) {
            Object val = data.get(key);
            if (val != null && !val.toString().isEmpty()) {
                return val.toString();
            }
        }
        if (data.get("data") instanceof Map<?, ?> nested) {
            return extractString((Map<String, Object>) nested, keys);
        }
        return null;
    }

    private List<Map<String, Object>> asList(Map<String, Object> data) {
        if (data == null) return Collections.emptyList();
        if (data.get("data") instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        if (data.get("result") instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        if (data.get("orders") instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        if (data.get("positions") instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }
}
