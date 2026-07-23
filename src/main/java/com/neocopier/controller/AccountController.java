package com.neocopier.controller;

import com.neocopier.model.Account;
import com.neocopier.service.AccountService;
import com.neocopier.service.FeedService;
import com.neocopier.service.ScripService;
import com.neocopier.util.TotpUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final FeedService feedService;
    private final ScripService scripService;

    public AccountController(AccountService accountService, FeedService feedService, ScripService scripService) {
        this.accountService = accountService;
        this.feedService = feedService;
        this.scripService = scripService;
    }

    @GetMapping
    public List<Map<String, Object>> getAccounts() {
        return accountService.getAllAccounts().stream().map(this::publicAccountMap).toList();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> saveAccount(@RequestBody Account body) {
        if (body.getNickname() == null || body.getRole() == null || body.getMobileNumber() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required fields (nickname, role, mobileNumber)"));
        }
        Account saved = accountService.saveAccount(body);
        feedService.connectMarketFeed();
        return ResponseEntity.ok(Map.of("success", true, "accountId", saved.getId()));
    }

    @DeleteMapping("/{account_id}")
    public Map<String, Boolean> deleteAccount(@PathVariable("account_id") String accountId) {
        accountService.deleteAccount(accountId);
        feedService.connectMarketFeed();
        return Map.of("success", true);
    }

    @PostMapping("/{account_id}/login")
    public ResponseEntity<Map<String, Object>> loginAccount(@PathVariable("account_id") String accountId,
                                                            @RequestBody(required = false) Map<String, String> body) {
        String manualOtp = body != null ? body.get("manualOtp") : null;
        Map<String, Object> res = accountService.loginAccount(accountId, manualOtp);
        feedService.connectMarketFeed();
        return ResponseEntity.ok(res);
    }

    @PostMapping("/refresh-all")
    public Map<String, Object> refreshAllAccounts() {
        List<Map<String, Object>> results = accountService.refreshAllAccounts();
        feedService.connectMarketFeed();
        return Map.of("success", true, "results", results);
    }

    @GetMapping("/{account_id}/totp-preview")
    public ResponseEntity<Map<String, String>> totpPreview(@PathVariable("account_id") String accountId) {
        Optional<Account> accOpt = accountService.getAccountById(accountId);
        if (accOpt.isEmpty() || !TotpUtils.hasAutoTotpSecret(accOpt.get().getTotpSecret())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Reusable TOTP secret not found"));
        }
        return ResponseEntity.ok(Map.of("code", TotpUtils.generateTotp(accOpt.get().getTotpSecret())));
    }

    @GetMapping("/margins")
    public List<Map<String, Object>> getMargins() {
        return accountService.getMargins();
    }

    @GetMapping("/positions")
    public List<Map<String, Object>> getPositions() {
        return accountService.getPositions(scripService);
    }

    @GetMapping("/feed-credentials")
    public ResponseEntity<Map<String, Object>> getFeedCredentials() {
        Optional<Account> activeAcc = accountService.getFirstActiveAccount();
        if (activeAcc.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "No active account for live feed"));
        }
        Account acc = activeAcc.get();
        return ResponseEntity.ok(Map.of(
                "accessToken", acc.getAccessToken() != null ? acc.getAccessToken() : (acc.getConsumerKey() != null ? acc.getConsumerKey() : ""),
                "sid", acc.getSid() != null ? acc.getSid() : "",
                "serverId", acc.getHsServerId() != null ? acc.getHsServerId() : "",
                "dataCenter", acc.getDataCenter() != null ? acc.getDataCenter() : ""
        ));
    }

    private Map<String, Object> publicAccountMap(Account a) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", a.getId());
        map.put("nickname", a.getNickname());
        map.put("role", a.getRole());
        map.put("mobileNumber", a.getMobileNumber());
        map.put("ucc", a.getUcc() != null ? a.getUcc() : "");
        map.put("multiplier", a.getMultiplier() != null ? a.getMultiplier() : 1.0);
        map.put("status", a.getStatus() != null ? a.getStatus() : "disconnected");
        map.put("lastLogin", a.getLastLogin());
        map.put("errorMessage", a.getErrorMessage());
        map.put("createdAt", a.getCreatedAt());
        map.put("consumerKey", a.getConsumerKey() != null ? a.getConsumerKey() : "");
        map.put("mpin", a.getMpin() != null ? a.getMpin() : "");
        map.put("totpSecret", a.getTotpSecret() != null ? a.getTotpSecret() : "");
        map.put("hasConsumerKey", a.getConsumerKey() != null && !a.getConsumerKey().isEmpty());
        map.put("hasTotpSecret", a.getTotpSecret() != null && !a.getTotpSecret().isEmpty());
        map.put("hasAutoTotpSecret", TotpUtils.hasAutoTotpSecret(a.getTotpSecret()));
        map.put("hasMpin", a.getMpin() != null && !a.getMpin().isEmpty());
        return map;
    }
}
