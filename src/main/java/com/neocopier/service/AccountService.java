package com.neocopier.service;

import com.neocopier.client.KotakApiClient;
import com.neocopier.model.Account;
import com.neocopier.repository.AccountRepository;
import com.neocopier.util.TotpUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final KotakApiClient kotakApiClient;

    @PersistenceContext
    private EntityManager entityManager;

    public AccountService(AccountRepository accountRepository, KotakApiClient kotakApiClient) {
        this.accountRepository = accountRepository;
        this.kotakApiClient = kotakApiClient;
    }

    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    public Optional<Account> getAccountById(String id) {
        return accountRepository.findById(id);
    }

    public List<Account> getActiveAccounts() {
        return accountRepository.findByStatus("active");
    }

    public Optional<Account> getFirstActiveAccount() {
        Optional<Account> activeOpt = getActiveAccounts().stream().filter(a -> a.getConsumerKey() != null && !a.getConsumerKey().isEmpty()).findFirst();
        if (activeOpt.isPresent()) {
            return activeOpt;
        }
        List<Account> all = accountRepository.findAll();
        for (Account acc : all) {
            if (acc.getTotpSecret() != null && !acc.getTotpSecret().trim().isEmpty() && acc.getConsumerKey() != null && !acc.getConsumerKey().trim().isEmpty()) {
                log.info("[Account] Attempting auto-login for scrip master fetch on account: {}", acc.getNickname());
                Map<String, Object> res = loginAccount(acc.getId(), null);
                if (Boolean.TRUE.equals(res.get("success"))) {
                    return accountRepository.findById(acc.getId());
                }
            }
        }
        return Optional.empty();
    }

    public Account saveAccount(Account account) {
        if ("master".equalsIgnoreCase(account.getRole())) {
            List<Account> existingMasters = accountRepository.findByRole("master");
            for (Account master : existingMasters) {
                if (!master.getId().equals(account.getId())) {
                    master.setRole("slave");
                    accountRepository.save(master);
                    log.info("[Account] Demoted '{}' to slave as '{}' is now master.", master.getNickname(), account.getNickname());
                }
            }
        }

        if (account.getId() == null || account.getId().isEmpty()) {
            account.setId("ACC_" + System.currentTimeMillis() + "_" + (new Random().nextInt(900) + 100));
            account.setCreatedAt(LocalDateTime.now().toString());
        } else {
            Optional<Account> existingOpt = accountRepository.findById(account.getId());
            if (existingOpt.isPresent()) {
                Account existing = existingOpt.get();
                if (account.getTotpSecret() == null || account.getTotpSecret().trim().isEmpty()) {
                    account.setTotpSecret(existing.getTotpSecret());
                }
                if (account.getMpin() == null || account.getMpin().trim().isEmpty()) {
                    account.setMpin(existing.getMpin());
                }
                if (account.getConsumerKey() == null || account.getConsumerKey().trim().isEmpty()) {
                    account.setConsumerKey(existing.getConsumerKey());
                }
            }
        }

        if (account.getTotpSecret() != null) {
            account.setTotpSecret(account.getTotpSecret().replaceAll("\\s+", ""));
        }

        return accountRepository.save(account);
    }

    public void deleteAccount(String accountId) {
        Optional<Account> targetOpt = accountRepository.findById(accountId);
        boolean isMaster = targetOpt.isPresent() && "master".equalsIgnoreCase(targetOpt.get().getRole());

        accountRepository.deleteById(accountId);

        if (isMaster) {
            List<Account> remaining = accountRepository.findAll();
            if (!remaining.isEmpty()) {
                Account nextMaster = remaining.get(0);
                nextMaster.setRole("master");
                accountRepository.save(nextMaster);
                log.info("[Account] Auto-promoted '{}' to master after deleting previous master.", nextMaster.getNickname());
            }
        }
    }

    public Map<String, Object> loginAccount(String accountId, String manualOtp) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        try {
            Map<String, Object> authResult = kotakApiClient.authenticate(account, manualOtp);
            if (Boolean.TRUE.equals(authResult.get("success"))) {
                account.setStatus("active");
                account.setAccessToken((String) authResult.get("accessToken"));
                account.setSid((String) authResult.get("sid"));
                account.setNeoToken((String) authResult.get("neoToken"));
                account.setRid((String) authResult.get("rid"));
                account.setHsServerId((String) authResult.get("hsServerId"));
                account.setDataCenter((String) authResult.get("dataCenter"));
                account.setBaseUrl((String) authResult.get("baseUrl"));
                account.setLastLogin(LocalDateTime.now().toString());
                account.setErrorMessage(null);
            } else {
                account.setStatus("error");
                account.setErrorMessage((String) authResult.get("error"));
            }
        } catch (Exception e) {
            account.setStatus("error");
            account.setErrorMessage(e.getMessage());
        }

        accountRepository.save(account);
        Map<String, Object> res = new HashMap<>();
        res.put("success", "active".equals(account.getStatus()));
        res.put("status", account.getStatus());
        res.put("error", account.getErrorMessage() != null ? account.getErrorMessage() : "");
        return res;
    }

    public List<Map<String, Object>> refreshAllAccounts() {
        List<Account> accounts = accountRepository.findAll();
        if (accounts.isEmpty()) return Collections.emptyList();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Map<String, Object>>> futures = accounts.stream()
                    .map(acc -> CompletableFuture.supplyAsync(() -> {
                        Map<String, Object> res = new HashMap<>();
                        res.put("id", acc.getId());
                        res.put("nickname", acc.getNickname());
                        if (!TotpUtils.hasAutoTotpSecret(acc.getTotpSecret())) {
                            res.put("success", false);
                            res.put("error", "Current TOTP required for this account");
                            return res;
                        }
                        Map<String, Object> loginRes = loginAccount(acc.getId(), null);
                        res.put("success", loginRes.get("success"));
                        res.put("error", loginRes.get("error"));
                        return res;
                    }, executor))
                    .toList();

            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    public List<Map<String, Object>> getMargins() {
        List<Account> activeAccs = getActiveAccounts();
        if (activeAccs.isEmpty()) return Collections.emptyList();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Map<String, Object>>> futures = activeAccs.stream()
                    .map(acc -> CompletableFuture.supplyAsync(() -> {
                        Map<String, Object> res = new HashMap<>();
                        res.put("accountId", acc.getId());
                        res.put("accountName", acc.getNickname());
                        res.put("role", acc.getRole());
                        try {
                            Map<String, Object> limits = kotakApiClient.getLimits(acc);
                            double net = parseDouble(limits.get("Net"), limits.get("net"));
                            double marginUsed = parseDouble(limits.get("MarginUsed"), limits.get("marginUsed"));
                            double collateral = Math.max(0, parseDouble(limits.get("CollateralValue"), limits.get("collateralValue")));

                            res.put("cashBalance", net + marginUsed - collateral);
                            res.put("utilMargin", marginUsed);
                            res.put("availableMargin", net);
                            res.put("collateral", collateral);
                            res.put("realizedPL", parseDouble(limits.get("RealizedMtomPrsnt"), limits.get("realizedMtomPrsnt")));
                            res.put("unrealizedPL", parseDouble(limits.get("UnrealizedMtomPrsnt"), limits.get("unrealizedMtomPrsnt")));
                        } catch (Exception e) {
                            res.put("error", e.getMessage());
                            res.put("availableMargin", 0.0);
                        }
                        return res;
                    }, executor))
                    .toList();

            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    public List<Map<String, Object>> getPositions(ScripService scripService) {
        List<Account> activeAccs = getActiveAccounts();
        if (activeAccs.isEmpty()) return Collections.emptyList();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Map<String, Object>>> futures = activeAccs.stream()
                    .map(acc -> CompletableFuture.supplyAsync(() -> {
                        Map<String, Object> accRes = new HashMap<>();
                        accRes.put("accountId", acc.getId());
                        accRes.put("accountName", acc.getNickname());
                        accRes.put("role", acc.getRole());
                        try {
                            List<Map<String, Object>> rawPositions = kotakApiClient.getPositions(acc);
                            List<Map<String, Object>> formatted = new ArrayList<>();

                            for (Map<String, Object> p : rawPositions) {
                                double buyQty = parseDouble(p.get("flBuyQty"), p.get("buyQty"));
                                double sellQty = parseDouble(p.get("flSellQty"), p.get("sellQty"));
                                double netQty = parseDouble(p.get("flQty"), p.get("netQty"), buyQty - sellQty);

                                double buyAmt = parseDouble(p.get("buyAmt"));
                                double buyAvg = parseDouble(p.get("buyAvg"), p.get("buyAvgRate"), p.get("buyRate"));
                                if (buyAvg == 0 && buyQty > 0) buyAvg = buyAmt / buyQty;

                                double sellAmt = parseDouble(p.get("sellAmt"));
                                double sellAvg = parseDouble(p.get("sellAvg"), p.get("sellAvgRate"), p.get("sellRate"));
                                if (sellAvg == 0 && sellQty > 0) sellAvg = sellAmt / sellQty;

                                double ltp = parseDouble(p.get("ltp"), p.get("lastPrice"), p.get("actvLtp"));

                                String tokenStr = parseString(p.get("tok"), p.get("scrpCd"), p.get("token"), p.get("instrumentToken"));
                                String symStr = parseString(p.get("trdSym"), p.get("tradingSymbol"), p.get("symbol"));

                                var scripInfo = scripService.lookupByTokenOrSymbol(tokenStr, symStr);
                                String scripRef = scripInfo != null ? scripInfo.getScripRefKey() : parseString(p.get("scripRefKey"), symStr);
                                double strike = scripInfo != null ? scripInfo.getStrikePrice() : parseDouble(p.get("stkPrc"), p.get("strikePrice"));
                                String expiry = scripInfo != null && scripInfo.getExpiry() != null ? scripInfo.getExpiry().toString() : parseString(p.get("expDate"), p.get("expiryDate"));

                                Map<String, Object> f = new HashMap<>();
                                f.put("accountId", acc.getId());
                                f.put("accountName", acc.getNickname());
                                f.put("symbol", symStr);
                                f.put("scripRefKey", scripRef);
                                f.put("scriptToken", tokenStr);
                                f.put("segment", parseString(p.get("prod"), p.get("prdCd"), p.get("product")));
                                f.put("exchange", parseString(p.get("exSeg"), p.get("exch"), p.get("exchange")));
                                f.put("netQty", netQty);
                                f.put("buyQty", buyQty);
                                f.put("sellQty", sellQty);
                                f.put("buyAvg", buyAvg);
                                f.put("sellAvg", sellAvg);
                                f.put("actvLtp", ltp);
                                f.put("strikePrice", strike);
                                f.put("expiry", expiry);
                                formatted.add(f);
                            }
                            Collections.reverse(formatted);
                            accRes.put("positions", formatted);

                        } catch (Exception e) {
                            accRes.put("error", e.getMessage());
                            accRes.put("positions", Collections.emptyList());
                        }
                        return accRes;
                    }, executor))
                    .toList();

            List<Map<String, Object>> results = new ArrayList<>(futures.stream().map(CompletableFuture::join).toList());
            results.sort((a, b) -> "master".equalsIgnoreCase((String) a.get("role")) ? -1 : 1);
            return results;
        }
    }

    @Scheduled(fixedDelay = 300000)
    public void validateActiveAccountSessions() {
        List<Account> activeAccs = getActiveAccounts();
        if (activeAccs.isEmpty()) return;

        log.info("[Session] Periodic check: validating {} active account sessions...", activeAccs.size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Account acc : activeAccs) {
                executor.submit(() -> {
                    try {
                        Map<String, Object> limits = kotakApiClient.getLimits(acc);
                        if (limits == null || limits.isEmpty() || limits.containsKey("error")) {
                            log.warn("[Session] Session validation check failed for {}: {}", acc.getNickname(), limits != null ? limits.get("error") : "Session response empty");
                            acc.setStatus("disconnected");
                            acc.setErrorMessage("Session expired. Please login again.");
                            accountRepository.save(acc);
                        }
                    } catch (Exception e) {
                        log.warn("[Session] Session validation check failed for {}: {}", acc.getNickname(), e.getMessage());
                        acc.setStatus("disconnected");
                        acc.setErrorMessage("Session expired. Please login again.");
                        accountRepository.save(acc);
                    }
                });
            }
        }
    }

    private double parseDouble(Object... values) {
        for (Object v : values) {
            if (v != null) {
                try {
                    return Double.parseDouble(v.toString());
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0.0;
    }

    private String parseString(Object... values) {
        for (Object v : values) {
            if (v != null && !v.toString().isEmpty()) {
                return v.toString();
            }
        }
        return "";
    }
}
