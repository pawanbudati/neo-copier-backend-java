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
        String baseUrl = getBaseUrl(account);
        String totpSecret = account.getTotpSecret();
        String mpin = account.getMpin();

        String totpCode = null;
        if (manualOtp != null && !manualOtp.trim().isEmpty()) {
            totpCode = manualOtp.trim();
        } else if (totpSecret != null && !totpSecret.trim().isEmpty()) {
            totpCode = com.neocopier.util.TotpUtils.generateTotp(totpSecret);
        }

        if (totpCode == null || totpCode.trim().isEmpty()) {
            throw new RuntimeException("TOTP secret or manual OTP is required for account authentication.");
        }

        Map<String, Object> payload1 = Map.of(
                "mobileNumber", account.getMobileNumber() != null ? account.getMobileNumber() : "",
                "ucc", account.getUcc() != null ? account.getUcc() : "",
                "totp", totpCode
        );

        Map<String, Object> step1Res = postRequest(baseUrl + "/oauth2/token", payload1, account.getConsumerKey(), null, null);
        String token = (String) step1Res.get("token");
        String sid = (String) step1Res.get("sid");

        if (token == null || sid == null) {
            String err = (String) step1Res.getOrDefault("message", step1Res.getOrDefault("error", "TOTP authentication failed"));
            throw new RuntimeException(err);
        }

        Map<String, Object> payload2 = Map.of(
                "mpin", mpin != null ? mpin : ""
        );

        Map<String, Object> step2Res = postRequest(baseUrl + "/oauth2/token/mpin", payload2, account.getConsumerKey(), sid, token);
        String neoToken = (String) step2Res.get("token");
        String hsServerId = (String) step2Res.get("hsServerId");

        if (neoToken == null) {
            String err = (String) step2Res.getOrDefault("message", step2Res.getOrDefault("error", "MPIN authentication failed"));
            throw new RuntimeException(err);
        }

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("accessToken", token);
        res.put("sid", sid);
        res.put("neoToken", neoToken);
        res.put("rid", (String) step2Res.get("rid"));
        res.put("hsServerId", hsServerId);
        res.put("dataCenter", (String) step2Res.get("dataCenter"));
        res.put("baseUrl", baseUrl);
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
                builder.header("Authorization", "Bearer " + consumerKey);
            }
            if (sid != null && !sid.isEmpty()) {
                builder.header("sid", sid);
            }
            if (neoToken != null && !neoToken.isEmpty()) {
                builder.header("neo-fin-key", neoToken);
                builder.header("Auth", neoToken);
            }
            builder.header("Content-Type", "application/json");

            if ("POST".equalsIgnoreCase(method)) {
                String jsonBody = body != null ? objectMapper.writeValueAsString(body) : "{}";
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                builder.GET();
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

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
            log.error("[KotakApiClient] Request failed: {} {} - {}", method, url, e.getMessage());
            Map<String, Object> errMap = new HashMap<>();
            errMap.put("error", e.getMessage());
            return errMap;
        }
    }
}
