package com.neocopier.controller;

import com.neocopier.client.KotakApiClient;
import com.neocopier.model.Account;
import com.neocopier.model.OcoPair;
import com.neocopier.service.AccountService;
import com.neocopier.service.OcoService;
import com.neocopier.service.TradingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/positions")
public class PositionController {

    private final AccountService accountService;
    private final TradingService tradingService;
    private final OcoService ocoService;
    private final KotakApiClient kotakApiClient;

    public PositionController(AccountService accountService,
                              TradingService tradingService,
                              OcoService ocoService,
                              KotakApiClient kotakApiClient) {
        this.accountService = accountService;
        this.tradingService = tradingService;
        this.ocoService = ocoService;
        this.kotakApiClient = kotakApiClient;
    }

    @PostMapping("/exit")
    public ResponseEntity<Map<String, Object>> exitPosition(@RequestBody Map<String, Object> body) {
        String accountId = (String) body.get("accountId");
        String symbol = (String) body.get("symbol");
        Object qtyObj = body.get("quantity");
        if (accountId == null || symbol == null || qtyObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required details to exit position"));
        }

        double qty = Double.parseDouble(qtyObj.toString());
        if (qty == 0) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Position already flat"));
        }

        Optional<Account> accOpt = accountService.getAccountById(accountId);
        if (accOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Account not found"));
        }
        Account account = accOpt.get();

        String transactionType = qty > 0 ? "SELL" : "BUY";
        int targetQty = Math.abs((int) qty);

        Map<String, Object> orderInput = Map.of(
                "symbol", symbol,
                "instrument", "CUSTOM",
                "optionType", body.getOrDefault("segment", "MIS"),
                "strikePrice", 0,
                "expiry", "",
                "quantity", targetQty,
                "price", 0,
                "triggerPrice", 0,
                "orderType", "MARKET",
                "transactionType", transactionType
        );

        if ("master".equalsIgnoreCase(account.getRole())) {
            Map<String, Object> result = tradingService.replicateMasterTrade(orderInput);
            return ResponseEntity.ok(Map.of("success", true, "result", result));
        } else {
            Map<String, Object> result = tradingService.executeOrder(account, orderInput);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                return ResponseEntity.status(500).body(Map.of("error", result.getOrDefault("error", "Failed to place square off order")));
            }
            return ResponseEntity.ok(Map.of("success", true, "result", result));
        }
    }

    @PostMapping("/exit-all")
    public Map<String, Object> exitAllPositions() {
        List<Account> activeAccs = accountService.getActiveAccounts();
        if (activeAccs.isEmpty()) {
            return Map.of("success", true, "totalExits", 0, "details", Collections.emptyList());
        }

        List<Map<String, Object>> details = new ArrayList<>();
        int totalExits = 0;

        for (Account acc : activeAccs) {
            Map<String, Object> accDetail = new HashMap<>();
            accDetail.put("accountName", acc.getNickname());
            List<Map<String, Object>> exits = new ArrayList<>();

            try {
                List<Map<String, Object>> positions = kotakApiClient.getPositions(acc);
                for (Map<String, Object> p : positions) {
                    double qty = Double.parseDouble(p.getOrDefault("flQty", 0).toString());
                    if (qty == 0) continue;
                    String symbol = (String) p.getOrDefault("trdSym", p.get("symbol"));

                    Map<String, Object> res = tradingService.executeOrder(acc, Map.of(
                            "symbol", symbol,
                            "instrument", "CUSTOM",
                            "optionType", "MIS",
                            "quantity", Math.abs((int) qty),
                            "orderType", "MARKET",
                            "transactionType", qty > 0 ? "SELL" : "BUY"
                    ));

                    if (Boolean.TRUE.equals(res.get("success"))) {
                        totalExits++;
                    }
                    exits.add(Map.of("symbol", symbol, "success", res.get("success"), "orderId", res.getOrDefault("orderId", ""), "error", res.getOrDefault("error", "")));
                }
            } catch (Exception e) {
                accDetail.put("error", e.getMessage());
            }
            accDetail.put("exits", exits);
            details.add(accDetail);
        }

        return Map.of("success", true, "totalExits", totalExits, "details", details);
    }

    @PostMapping("/oco")
    public ResponseEntity<Map<String, Object>> createOco(@RequestBody Map<String, Object> body) {
        String accountId = (String) body.get("accountId");
        Account account = null;
        if (accountId != null) {
            account = accountService.getAccountById(accountId).orElse(null);
        }
        if (account == null) {
            account = accountService.getFirstActiveAccount().orElse(null);
        }
        if (account == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Account not found. Active broker session required."));
        }
        if (!"active".equalsIgnoreCase(account.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Account session is inactive"));
        }

        Map<String, Object> res = ocoService.createOcoBracket(account, body);
        if (!Boolean.TRUE.equals(res.get("success"))) {
            return ResponseEntity.badRequest().body(Map.of("error", res.getOrDefault("error", "Failed to place OCO orders")));
        }

        return ResponseEntity.ok(res);
    }

    @GetMapping("/oco/active")
    public List<OcoPair> getActiveOcos() {
        return ocoService.getActiveOcoPairs();
    }

    @DeleteMapping("/oco/{oco_id}")
    public ResponseEntity<Map<String, Object>> deleteOco(@PathVariable("oco_id") String ocoId) {
        Map<String, Object> res = ocoService.cancelOcoBracket(ocoId);
        if (!Boolean.TRUE.equals(res.get("success"))) {
            return ResponseEntity.badRequest().body(Map.of("error", res.getOrDefault("error", "Failed to cancel OCO bracket")));
        }
        return ResponseEntity.ok(res);
    }
}
