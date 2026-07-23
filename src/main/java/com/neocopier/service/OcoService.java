package com.neocopier.service;

import com.neocopier.model.Account;
import com.neocopier.model.OcoPair;
import com.neocopier.repository.AccountRepository;
import com.neocopier.repository.OcoPairRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;

@Service
public class OcoService {

    private static final Logger log = LoggerFactory.getLogger(OcoService.class);

    private final OcoPairRepository ocoPairRepository;
    private final AccountRepository accountRepository;
    private final TradingService tradingService;

    public OcoService(OcoPairRepository ocoPairRepository,
                      AccountRepository accountRepository,
                      TradingService tradingService) {
        this.ocoPairRepository = ocoPairRepository;
        this.accountRepository = accountRepository;
        this.tradingService = tradingService;
    }

    public List<OcoPair> getActiveOcoPairs() {
        return ocoPairRepository.findByStatus("PENDING");
    }

    public Map<String, Object> createOcoBracket(Account account, Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        String segment = (String) body.getOrDefault("segment", "MIS");
        int quantity = Integer.parseInt(body.getOrDefault("quantity", 0).toString());
        double slTrigger = Double.parseDouble(body.getOrDefault("slTriggerPrice", 0).toString());
        double slLimit = Double.parseDouble(body.getOrDefault("slLimitPrice", 0).toString());
        double tpPrice = Double.parseDouble(body.getOrDefault("tpPrice", 0).toString());
        String txType = (String) body.getOrDefault("transactionType", "SELL");

        cancelExistingOcosForPosition(account, symbol);

        if ("master".equalsIgnoreCase(account.getRole())) {
            return replicateMasterOco(account, symbol, segment, quantity, slTrigger, slLimit, tpPrice, txType);
        } else {
            return placeOcoOrders(account, symbol, segment, quantity, slTrigger, slLimit, tpPrice, txType, null);
        }
    }

    public Map<String, Object> placeOcoOrders(Account account, String symbol, String segment, int quantity,
                                              double slTriggerPrice, double slLimitPrice, double tpPrice,
                                              String transactionType, String masterOcoId) {
        Map<String, Object> slRes = null;
        Map<String, Object> tpRes = null;

        if (slTriggerPrice > 0) {
            String slOrderType = slLimitPrice > 0 ? "SL" : "SL-M";
            double slPx = slLimitPrice > 0 ? slLimitPrice : 0.0;

            Map<String, Object> slInput = Map.of(
                    "symbol", symbol,
                    "instrument", "CUSTOM",
                    "optionType", segment,
                    "quantity", quantity,
                    "price", slPx,
                    "triggerPrice", slTriggerPrice,
                    "orderType", slOrderType,
                    "transactionType", transactionType
            );
            slRes = tradingService.executeOrder(account, slInput);
            if (!Boolean.TRUE.equals(slRes.get("success"))) {
                return Map.of("success", false, "error", "Failed to place Stop Loss order: " + slRes.get("error"));
            }
        }

        if (tpPrice > 0) {
            Map<String, Object> tpInput = Map.of(
                    "symbol", symbol,
                    "instrument", "CUSTOM",
                    "optionType", segment,
                    "quantity", quantity,
                    "price", tpPrice,
                    "orderType", "LIMIT",
                    "transactionType", transactionType
            );
            tpRes = tradingService.executeOrder(account, tpInput);
            if (!Boolean.TRUE.equals(tpRes.get("success"))) {
                if (slRes != null && slRes.get("orderId") != null) {
                    tradingService.cancelOrderOnBroker(account, (String) slRes.get("orderId"));
                }
                return Map.of("success", false, "error", "Failed to place Target order: " + tpRes.get("error"));
            }
        }

        if (slRes == null && tpRes == null) {
            return Map.of("success", false, "error", "Please enter at least a Target Price or Stop Loss Trigger Price.");
        }

        String ocoId = "OCO_" + System.currentTimeMillis() + "_" + account.getId();
        if (slRes != null && tpRes != null) {
            OcoPair pair = OcoPair.builder()
                    .id(ocoId)
                    .masterOcoId(masterOcoId)
                    .accountId(account.getId())
                    .accountName(account.getNickname())
                    .symbol(symbol)
                    .quantity(quantity)
                    .slOrderId((String) slRes.get("orderId"))
                    .tpOrderId((String) tpRes.get("orderId"))
                    .slTriggerPrice(slTriggerPrice)
                    .slLimitPrice(slLimitPrice)
                    .tpPrice(tpPrice)
                    .transactionType(transactionType)
                    .status("PENDING")
                    .timestamp(LocalDateTime.now().toString())
                    .build();

            ocoPairRepository.save(pair);
        }

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("ocoId", ocoId);
        res.put("slOrderId", slRes != null ? slRes.get("orderId") : null);
        res.put("tpOrderId", tpRes != null ? tpRes.get("orderId") : null);
        return res;
    }

    public Map<String, Object> replicateMasterOco(Account master, String symbol, String segment, int quantity,
                                                 double slTriggerPrice, double slLimitPrice, double tpPrice,
                                                 String transactionType) {
        Map<String, Object> masterRes = placeOcoOrders(master, symbol, segment, quantity, slTriggerPrice, slLimitPrice, tpPrice, transactionType, null);
        if (!Boolean.TRUE.equals(masterRes.get("success"))) {
            return masterRes;
        }

        String masterOcoId = (String) masterRes.get("ocoId");
        List<Account> slaves = accountRepository.findAll().stream()
                .filter(a -> "slave".equalsIgnoreCase(a.getRole()) && "active".equalsIgnoreCase(a.getStatus()))
                .toList();

        if (!slaves.isEmpty()) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Account slave : slaves) {
                    executor.submit(() -> {
                        double mult = slave.getMultiplier() != null ? slave.getMultiplier() : 1.0;
                        int slaveQty = Math.max(1, (int) Math.round(quantity * mult));
                        placeOcoOrders(slave, symbol, segment, slaveQty, slTriggerPrice, slLimitPrice, tpPrice, transactionType, masterOcoId);
                    });
                }
            }
        }

        return Map.of("success", true, "ocoId", masterOcoId);
    }

    public Map<String, Object> cancelOcoBracket(String ocoId) {
        Optional<OcoPair> pairOpt = ocoPairRepository.findById(ocoId);
        if (pairOpt.isEmpty()) {
            return Map.of("success", false, "error", "OCO bracket not found");
        }

        OcoPair pair = pairOpt.get();
        Optional<Account> accOpt = accountRepository.findById(pair.getAccountId());
        if (accOpt.isPresent() && "active".equalsIgnoreCase(accOpt.get().getStatus())) {
            Account acc = accOpt.get();
            if (pair.getSlOrderId() != null) tradingService.cancelOrderOnBroker(acc, pair.getSlOrderId());
            if (pair.getTpOrderId() != null) tradingService.cancelOrderOnBroker(acc, pair.getTpOrderId());
        }

        pair.setStatus("CANCELLED");
        ocoPairRepository.save(pair);

        if (pair.getMasterOcoId() == null) {
            List<OcoPair> slavePairs = ocoPairRepository.findByMasterOcoIdAndStatus(ocoId, "PENDING");
            if (!slavePairs.isEmpty()) {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (OcoPair sPair : slavePairs) {
                        executor.submit(() -> cancelOcoBracket(sPair.getId()));
                    }
                }
            }
        }
        return Map.of("success", true);
    }

    public void cancelExistingOcosForPosition(Account account, String symbol) {
        List<OcoPair> activePairs = getActiveOcoPairs();
        boolean isMaster = "master".equalsIgnoreCase(account.getRole());

        for (OcoPair pair : activePairs) {
            if (symbol.equalsIgnoreCase(pair.getSymbol())) {
                if (isMaster || account.getId().equals(pair.getAccountId())) {
                    log.info("[OCO] Cancelling existing OCO pair {} for account {}", pair.getId(), pair.getAccountName());
                    cancelOcoBracket(pair.getId());
                }
            }
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void checkOcoOrdersPeriodically() {
        List<OcoPair> activePairs = getActiveOcoPairs();
        if (activePairs.isEmpty()) return;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (OcoPair pair : activePairs) {
                executor.submit(() -> {
                    try {
                        Optional<Account> accOpt = accountRepository.findById(pair.getAccountId());
                        if (accOpt.isEmpty() || !"active".equalsIgnoreCase(accOpt.get().getStatus())) return;
                        Account account = accOpt.get();

                        Map<String, Object> slLive = tradingService.getOrderLiveStatus(account, pair.getSlOrderId());
                        Map<String, Object> tpLive = tradingService.getOrderLiveStatus(account, pair.getTpOrderId());

                        if (slLive != null && "SUCCESS".equalsIgnoreCase((String) slLive.get("status"))) {
                            tradingService.cancelOrderOnBroker(account, pair.getTpOrderId());
                            pair.setStatus("SL_TRIGGERED");
                            ocoPairRepository.save(pair);
                            log.info("[OCO] SL triggered for {}, cancelled TP order {}", pair.getAccountName(), pair.getTpOrderId());
                            if (pair.getMasterOcoId() == null) {
                                propagateOcoCancelTp(pair.getId());
                            }
                        } else if (tpLive != null && "SUCCESS".equalsIgnoreCase((String) tpLive.get("status"))) {
                            tradingService.cancelOrderOnBroker(account, pair.getSlOrderId());
                            pair.setStatus("TP_TRIGGERED");
                            ocoPairRepository.save(pair);
                            log.info("[OCO] TP triggered for {}, cancelled SL order {}", pair.getAccountName(), pair.getSlOrderId());
                            if (pair.getMasterOcoId() == null) {
                                propagateOcoCancelSl(pair.getId());
                            }
                        }
                    } catch (Exception e) {
                        log.error("[OCO] Error checking OCO pair {}: {}", pair.getId(), e.getMessage());
                    }
                });
            }
        }
    }

    private void propagateOcoCancelTp(String masterOcoId) {
        List<OcoPair> slavePairs = ocoPairRepository.findByMasterOcoIdAndStatus(masterOcoId, "PENDING");
        for (OcoPair s : slavePairs) {
            accountRepository.findById(s.getAccountId()).ifPresent(acc -> {
                tradingService.cancelOrderOnBroker(acc, s.getTpOrderId());
                s.setStatus("SL_TRIGGERED");
                ocoPairRepository.save(s);
            });
        }
    }

    private void propagateOcoCancelSl(String masterOcoId) {
        List<OcoPair> slavePairs = ocoPairRepository.findByMasterOcoIdAndStatus(masterOcoId, "PENDING");
        for (OcoPair s : slavePairs) {
            accountRepository.findById(s.getAccountId()).ifPresent(acc -> {
                tradingService.cancelOrderOnBroker(acc, s.getSlOrderId());
                s.setStatus("TP_TRIGGERED");
                ocoPairRepository.save(s);
            });
        }
    }
}
