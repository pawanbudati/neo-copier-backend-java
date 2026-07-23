package com.neocopier.service;

import com.neocopier.client.KotakApiClient;
import com.neocopier.model.Account;
import com.neocopier.model.Order;
import com.neocopier.model.Scrip;
import com.neocopier.repository.AccountRepository;
import com.neocopier.repository.OrderRepository;
import com.neocopier.util.ScripParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Service
public class TradingService {

    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final KotakApiClient kotakApiClient;
    private final SettingsService settingsService;
    private final ScripService scripService;

    public TradingService(AccountRepository accountRepository,
                          OrderRepository orderRepository,
                          KotakApiClient kotakApiClient,
                          SettingsService settingsService,
                          ScripService scripService) {
        this.accountRepository = accountRepository;
        this.orderRepository = orderRepository;
        this.kotakApiClient = kotakApiClient;
        this.settingsService = settingsService;
        this.scripService = scripService;
    }

    public List<Order> getAllOrders() {
        return orderRepository.findAll();
    }

    public void clearOrders() {
        orderRepository.deleteAll();
    }

    public void deleteOrder(String orderId) {
        orderRepository.deleteById(orderId);
    }

    public Map<String, Object> executeOrder(Account account, Map<String, Object> orderInput) {
        String fallbackOrderId = "NEO_" + System.currentTimeMillis() + "_" + (new Random().nextInt(900) + 100);
        try {
            if (!"active".equalsIgnoreCase(account.getStatus())) {
                throw new RuntimeException("Account session expired or unauthenticated. Please login again.");
            }

            String symbol = (String) orderInput.get("symbol");
            String instrument = (String) orderInput.get("instrument");
            Scrip matchedScrip = scripService.lookupByTokenOrSymbol(null, symbol);

            String tradingSymbol = symbol;
            String exchange = getExchangeForInstrument(instrument);
            if (matchedScrip != null) {
                tradingSymbol = matchedScrip.getTradingSymbol();
                exchange = matchedScrip.getExchange();
                log.info("[Order] Resolved {} to {} from scrip master", symbol, tradingSymbol);
            }

            String transactionType = "BUY".equalsIgnoreCase((String) orderInput.get("transactionType")) ? "B" : "S";
            String orderTypeStr = (String) orderInput.get("orderType");
            String kotakOrderType = "MARKET".equalsIgnoreCase(orderTypeStr) ? "MKT" : "SL".equalsIgnoreCase(orderTypeStr) ? "SL" : "L";

            Map<String, Object> payload = new HashMap<>();
            payload.put("exchange_segment", ScripParser.mapToNeoExchange(exchange));
            payload.put("trading_symbol", tradingSymbol);
            payload.put("quantity", String.valueOf(orderInput.get("quantity")));
            payload.put("price", String.valueOf(orderInput.getOrDefault("price", 0)));
            payload.put("transaction_type", transactionType);
            payload.put("order_type", kotakOrderType);
            payload.put("product", "MIS");
            payload.put("validity", "DAY");
            payload.put("disclosed_quantity", "0");
            payload.put("market_protection", "0");
            payload.put("trigger_price", "SL".equalsIgnoreCase(orderTypeStr) ? String.valueOf(orderInput.getOrDefault("triggerPrice", 0)) : "0");
            payload.put("pf", "N");
            payload.put("amo", "NO");

            log.info("[Order] Placing {} order for {} on {} via Kotak API", orderInput.get("transactionType"), tradingSymbol, account.getNickname());
            Map<String, Object> response = kotakApiClient.placeOrder(account, payload);

            if ("NotOk".equalsIgnoreCase((String) response.get("stat")) || response.containsKey("errMsg") || response.containsKey("error")) {
                String err = parseString(response.get("errMsg"), response.get("error"), response.get("message"), response.toString());
                throw new RuntimeException(err);
            }

            String orderId = parseString(response.get("nOrdNo"), response.get("orderId"), fallbackOrderId);
            Map<String, Object> finalStatus = pollOrderFinalStatus(account, orderId);

            if (!Boolean.TRUE.equals(finalStatus.get("success"))) {
                return Map.of("success", true, "orderId", orderId, "status", finalStatus.getOrDefault("status", "FAILED"), "error", finalStatus.getOrDefault("error", "Order rejected"));
            }
            return Map.of("success", true, "orderId", orderId, "status", finalStatus.get("status"));

        } catch (Exception e) {
            log.error("[Order] Error for {}: {}", account.getNickname(), e.getMessage());
            return Map.of("success", false, "orderId", fallbackOrderId, "error", e.getMessage());
        }
    }

    public Map<String, Object> replicateMasterTrade(Map<String, Object> orderInput) {
        List<Account> accounts = accountRepository.findAll();
        Account master = accounts.stream()
                .filter(a -> "master".equalsIgnoreCase(a.getRole()) && "active".equalsIgnoreCase(a.getStatus()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No active Master Account found."));

        Map<String, Object> masterResult = executeOrder(master, orderInput);
        String masterOrderId = (String) masterResult.get("orderId");
        boolean masterSuccess = Boolean.TRUE.equals(masterResult.get("success"));

        Order masterOrder = Order.builder()
                .id(masterOrderId != null ? masterOrderId : "M_ERR_" + System.currentTimeMillis())
                .masterOrderId(null)
                .accountId(master.getId())
                .accountName(master.getNickname())
                .accountRole("master")
                .symbol((String) orderInput.get("symbol"))
                .instrument((String) orderInput.get("instrument"))
                .optionType((String) orderInput.getOrDefault("optionType", "EQ"))
                .strikePrice(parseDouble(orderInput.get("strikePrice")))
                .expiry((String) orderInput.getOrDefault("expiry", ""))
                .quantity((Integer) orderInput.get("quantity"))
                .price(parseDouble(orderInput.get("price")))
                .triggerPrice(parseDouble(orderInput.get("triggerPrice")))
                .orderType((String) orderInput.get("orderType"))
                .transactionType((String) orderInput.get("transactionType"))
                .status((String) masterResult.getOrDefault("status", masterSuccess ? "SUCCESS" : "FAILED"))
                .errorMessage((String) masterResult.get("error"))
                .timestamp(LocalDateTime.now().toString())
                .isSimulated(false)
                .build();

        orderRepository.save(masterOrder);

        List<Account> slaves = accounts.stream()
                .filter(a -> "slave".equalsIgnoreCase(a.getRole()) && "active".equalsIgnoreCase(a.getStatus()))
                .toList();

        List<Order> slaveOrders = new ArrayList<>();
        boolean autoReplicate = Boolean.TRUE.equals(settingsService.getSettings().getAutoReplicate());

        if (masterSuccess && autoReplicate && !slaves.isEmpty()) {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Order>> futures = slaves.stream()
                        .map(slave -> CompletableFuture.supplyAsync(() -> {
                            double mult = slave.getMultiplier() != null ? slave.getMultiplier() : 1.0;
                            int slaveQty = Math.max(1, (int) Math.round((Integer) orderInput.get("quantity") * mult));

                            Map<String, Object> slaveInput = new HashMap<>(orderInput);
                            slaveInput.put("quantity", slaveQty);

                            Map<String, Object> slaveResult = executeOrder(slave, slaveInput);
                            String slaveOrderId = (String) slaveResult.get("orderId");
                            boolean slaveSuccess = Boolean.TRUE.equals(slaveResult.get("success"));

                            return Order.builder()
                                    .id(slaveOrderId != null ? slaveOrderId : "S_ERR_" + System.currentTimeMillis() + "_" + slave.getId())
                                    .masterOrderId(masterOrder.getId())
                                    .accountId(slave.getId())
                                    .accountName(slave.getNickname())
                                    .accountRole("slave")
                                    .symbol((String) orderInput.get("symbol"))
                                    .instrument((String) orderInput.get("instrument"))
                                    .optionType((String) orderInput.getOrDefault("optionType", "EQ"))
                                    .strikePrice(parseDouble(orderInput.get("strikePrice")))
                                    .expiry((String) orderInput.getOrDefault("expiry", ""))
                                    .quantity(slaveQty)
                                    .price(parseDouble(orderInput.get("price")))
                                    .triggerPrice(parseDouble(orderInput.get("triggerPrice")))
                                    .orderType((String) orderInput.get("orderType"))
                                    .transactionType((String) orderInput.get("transactionType"))
                                    .status((String) slaveResult.getOrDefault("status", slaveSuccess ? "SUCCESS" : "FAILED"))
                                    .errorMessage((String) slaveResult.get("error"))
                                    .timestamp(LocalDateTime.now().toString())
                                    .isSimulated(false)
                                    .build();
                        }, executor))
                        .toList();

                slaveOrders = futures.stream().map(CompletableFuture::join).toList();
                orderRepository.saveAll(slaveOrders);
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("masterOrder", masterOrder);
        res.put("slaveOrders", slaveOrders);
        return res;
    }

    public Map<String, Object> cancelOrderOnBroker(Account account, String orderId) {
        try {
            Map<String, Object> response = kotakApiClient.cancelOrder(account, orderId);
            if ("NotOk".equalsIgnoreCase((String) response.get("stat")) || response.containsKey("errMsg") || response.containsKey("error")) {
                String err = parseString(response.get("errMsg"), response.get("error"), response.toString());
                throw new RuntimeException(err);
            }
            return Map.of("success", true);
        } catch (Exception e) {
            log.error("[CancelOrder] Error for {}: {}", account.getNickname(), e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    public Map<String, Object> modifyReplicatedTrade(String targetOrderId, Map<String, Object> updates) {
        Order order = orderRepository.findById(targetOrderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + targetOrderId));

        if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
            throw new RuntimeException("Cannot edit order with status '" + order.getStatus() + "'. Only PENDING orders can be edited.");
        }

        Account account = accountRepository.findById(order.getAccountId())
                .orElseThrow(() -> new RuntimeException("Account for target order not found."));

        Map<String, Object> live = getOrderLiveStatus(account, order.getId());
        if (live != null && !"PENDING".equalsIgnoreCase((String) live.get("status"))) {
            order.setStatus((String) live.get("status"));
            orderRepository.save(order);
            throw new RuntimeException("Order on broker is no longer pending (Live status: " + live.get("status") + "). Modification cancelled.");
        }

        Map<String, Object> modRes = modifyOrderExecution(account, order, updates);
        if (!Boolean.TRUE.equals(modRes.get("success"))) {
            throw new RuntimeException("Failed to modify order on broker: " + modRes.get("error"));
        }

        order.setPrice(parseDouble(modRes.get("price")));
        order.setQuantity((Integer) modRes.get("quantity"));
        order.setOrderType((String) modRes.get("orderType"));
        order.setTriggerPrice(parseDouble(modRes.get("triggerPrice")));
        orderRepository.save(order);

        List<Map<String, Object>> modifiedSlaves = new ArrayList<>();
        if ("master".equalsIgnoreCase(order.getAccountRole())) {
            List<Order> pendingSlaves = orderRepository.findByMasterOrderIdAndStatus(order.getId(), "PENDING");
            if (!pendingSlaves.isEmpty()) {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<CompletableFuture<Map<String, Object>>> futures = pendingSlaves.stream()
                            .map(sOrder -> CompletableFuture.supplyAsync(() -> {
                                Map<String, Object> res = new HashMap<>();
                                res.put("slaveId", sOrder.getId());

                                Optional<Account> sAccOpt = accountRepository.findById(sOrder.getAccountId());
                                if (sAccOpt.isEmpty() || !"active".equalsIgnoreCase(sAccOpt.get().getStatus())) {
                                    res.put("success", false);
                                    res.put("error", "Slave account inactive");
                                    return res;
                                }
                                Account sAcc = sAccOpt.get();
                                double mult = sAcc.getMultiplier() != null ? sAcc.getMultiplier() : 1.0;
                                int slaveQty = Math.max(1, (int) Math.round((Integer) modRes.get("quantity") * mult));

                                Map<String, Object> slaveUpdates = new HashMap<>(updates);
                                slaveUpdates.put("quantity", slaveQty);

                                Map<String, Object> sMod = modifyOrderExecution(sAcc, sOrder, slaveUpdates);
                                if (Boolean.TRUE.equals(sMod.get("success"))) {
                                    sOrder.setPrice(parseDouble(sMod.get("price")));
                                    sOrder.setQuantity((Integer) sMod.get("quantity"));
                                    sOrder.setOrderType((String) sMod.get("orderType"));
                                    sOrder.setTriggerPrice(parseDouble(sMod.get("triggerPrice")));
                                    orderRepository.save(sOrder);
                                    res.put("success", true);
                                } else {
                                    res.put("success", false);
                                    res.put("error", sMod.get("error"));
                                }
                                return res;
                            }, executor))
                            .toList();

                    modifiedSlaves = futures.stream().map(CompletableFuture::join).toList();
                }
            }
        }

        return Map.of("success", true, "updatedOrders", orderRepository.findAll(), "slaveResults", modifiedSlaves);
    }

    public Map<String, Object> modifyOrderExecution(Account account, Order order, Map<String, Object> updates) {
        try {
            Scrip scrip = scripService.lookupByTokenOrSymbol(null, order.getSymbol());
            String tradingSymbol = scrip != null ? scrip.getTradingSymbol() : order.getSymbol();
            String exchangeSegment = ScripParser.mapToNeoExchange(scrip != null ? scrip.getExchange() : "NSE");
            String instrumentToken = scrip != null ? scrip.getScriptToken() : "";

            double newPrice = updates.containsKey("price") ? parseDouble(updates.get("price")) : (order.getPrice() != null ? order.getPrice() : 0.0);
            int newQuantity = updates.containsKey("quantity") ? (Integer) updates.get("quantity") : (order.getQuantity() != null ? order.getQuantity() : 1);
            String newOrderType = updates.containsKey("orderType") ? (String) updates.get("orderType") : (order.getOrderType() != null ? order.getOrderType() : "MARKET");
            double newTriggerPrice = updates.containsKey("triggerPrice") ? parseDouble(updates.get("triggerPrice")) : (order.getTriggerPrice() != null ? order.getTriggerPrice() : 0.0);

            Map<String, Object> payload = new HashMap<>();
            payload.put("order_id", order.getId());
            payload.put("price", String.valueOf(newPrice));
            payload.put("quantity", String.valueOf(newQuantity));
            payload.put("order_type", "MARKET".equalsIgnoreCase(newOrderType) ? "MKT" : "SL".equalsIgnoreCase(newOrderType) ? "SL" : "L");
            payload.put("validity", "DAY");
            payload.put("product", "MIS");
            payload.put("trigger_price", "SL".equalsIgnoreCase(newOrderType) ? String.valueOf(newTriggerPrice) : "0");
            payload.put("transaction_type", "BUY".equalsIgnoreCase(order.getTransactionType()) ? "B" : "S");

            if (!instrumentToken.isEmpty() && !exchangeSegment.isEmpty() && !tradingSymbol.isEmpty()) {
                payload.put("instrument_token", instrumentToken);
                payload.put("exchange_segment", exchangeSegment);
                payload.put("trading_symbol", tradingSymbol);
            }

            Map<String, Object> response = kotakApiClient.modifyOrder(account, payload);
            if ("NotOk".equalsIgnoreCase((String) response.get("stat")) || response.containsKey("errMsg") || response.containsKey("error")) {
                String err = parseString(response.get("errMsg"), response.get("error"), response.toString());
                throw new RuntimeException(err);
            }

            return Map.of("success", true, "price", newPrice, "quantity", newQuantity, "orderType", newOrderType, "triggerPrice", newTriggerPrice);

        } catch (Exception e) {
            log.error("[OrderModify] Error for {}: {}", account.getNickname(), e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    public Map<String, Object> getOrderLiveStatus(Account account, String orderId) {
        try {
            List<Map<String, Object>> book = kotakApiClient.getOrderBook(account);
            for (Map<String, Object> order : book) {
                String id = parseString(order.get("nOrdNo"), order.get("order_id"), order.get("orderId"));
                if (id.equals(orderId)) {
                    String status = normalizeOrderStatus(parseString(order.get("ordSt"), order.get("status")));
                    Map<String, Object> res = new HashMap<>();
                    res.put("status", status);
                    res.put("rejReason", parseString(order.get("rejReason"), order.get("rejection_reason")));
                    res.put("cancelReason", parseString(order.get("cancelReason"), order.get("cancel_reason")));
                    return res;
                }
            }
        } catch (Exception e) {
            log.error("[OrderSync] Error checking order status for {}: {}", account.getNickname(), e.getMessage());
        }
        return null;
    }

    public Map<String, Object> pollOrderFinalStatus(Account account, String orderId) {
        for (int i = 0; i < 4; i++) {
            Map<String, Object> live = getOrderLiveStatus(account, orderId);
            if (live != null) {
                String status = (String) live.get("status");
                if ("FAILED".equals(status)) {
                    return Map.of("success", false, "status", "FAILED", "error", live.getOrDefault("rejReason", "Order rejected"));
                }
                if ("CANCELLED".equals(status)) {
                    return Map.of("success", false, "status", "FAILED", "error", live.getOrDefault("cancelReason", "Order cancelled"));
                }
                if ("SUCCESS".equals(status) || "PENDING".equals(status)) {
                    return Map.of("success", true, "status", status);
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ignored) {}
        }
        return Map.of("success", true, "status", "SUCCESS");
    }

    public Map<String, Object> calculateOrderMargins(Map<String, Object> payload) {
        String exSeg = ScripParser.mapToNeoExchange((String) payload.getOrDefault("exchange", "NFO"));
        String ot = (String) payload.getOrDefault("orderType", "MARKET");
        String kotakOt = "MARKET".equalsIgnoreCase(ot) ? "MKT" : "SL".equalsIgnoreCase(ot) ? "SL" : "L";
        String kotakTt = "BUY".equalsIgnoreCase((String) payload.getOrDefault("transactionType", "BUY")) ? "B" : "S";
        double priceVal = parseDouble(payload.get("price"));
        int qtyVal = (Integer) payload.getOrDefault("quantity", 1);
        String token = parseString(payload.get("scriptToken"), payload.get("instrumentToken"));

        List<Account> accounts = accountRepository.findByStatus("active");
        Account master = accounts.stream().filter(a -> "master".equalsIgnoreCase(a.getRole())).findFirst().orElse(null);
        List<Account> slaves = accounts.stream().filter(a -> "slave".equalsIgnoreCase(a.getRole())).toList();

        Map<String, Object> masterRes = master != null ? calcMarginForAccount(master, 1.0, exSeg, kotakOt, kotakTt, priceVal, qtyVal, token, payload) : null;
        List<Map<String, Object>> slavesRes = slaves.stream().map(s -> calcMarginForAccount(s, s.getMultiplier() != null ? s.getMultiplier() : 1.0, exSeg, kotakOt, kotakTt, priceVal, qtyVal, token, payload)).toList();

        return Map.of("unitPrice", priceVal, "master", masterRes != null ? masterRes : Collections.emptyMap(), "slaves", slavesRes);
    }

    private Map<String, Object> calcMarginForAccount(Account acc, double multiplier, String exSeg, String kotakOt, String kotakTt, double priceVal, int qtyVal, String token, Map<String, Object> payload) {
        int accQty = Math.max(1, (int) Math.round(qtyVal * multiplier));
        Map<String, Object> sdkPayload = Map.of(
                "exchange_segment", exSeg,
                "price", priceVal,
                "order_type", kotakOt,
                "product", payload.getOrDefault("product", "MIS"),
                "quantity", accQty,
                "instrument_token", token,
                "transaction_type", kotakTt,
                "trigger_price", parseDouble(payload.get("triggerPrice"))
        );

        Map<String, Object> limits = kotakApiClient.getLimits(acc);
        double netMargin = parseDouble(limits.get("Net"), limits.get("net"));
        double reqMargin = accQty * priceVal;

        try {
            Map<String, Object> res = kotakApiClient.marginRequired(acc, sdkPayload);
            Object mVal = res.get("marginRequired");
            if (mVal != null && mVal.toString().matches("\\d+(\\.\\d+)?")) {
                reqMargin = Double.parseDouble(mVal.toString());
            }
        } catch (Exception ignored) {}

        Map<String, Object> res = new HashMap<>();
        res.put("accountId", acc.getId());
        res.put("accountName", acc.getNickname());
        res.put("role", acc.getRole());
        res.put("multiplier", multiplier);
        res.put("quantity", accQty);
        res.put("requiredMargin", reqMargin);
        res.put("availableMargin", netMargin);
        res.put("sufficient", netMargin >= reqMargin);
        return res;
    }

    private String getExchangeForInstrument(String instrument) {
        String code = (instrument != null ? instrument : "").toUpperCase();
        if (code.contains("NIFTY") || code.contains("BANKNIFTY") || List.of("OPTIDX", "FUTIDX", "OPTSTK", "FUTSTK").contains(code)) return "NFO";
        if (code.contains("SENSEX") || List.of("IO", "IF").contains(code)) return "BFO";
        if (code.contains("USDINR") || List.of("OPTCUR", "FUTCUR").contains(code)) return "CDE";
        if (code.contains("CRUDE") || code.contains("GOLD") || List.of("FUTCOM", "OPTFUT").contains(code)) return "MCX";
        return "NSE";
    }

    private String normalizeOrderStatus(String raw) {
        String status = (raw != null ? raw : "").toUpperCase();
        if (List.of("TRADED", "COMPLETE", "COMPLETED", "FILLED").contains(status)) return "SUCCESS";
        if ("REJECTED".equals(status)) return "FAILED";
        if (List.of("CANCELLED", "CANCEL").contains(status)) return "CANCELLED";
        if (List.of("OPEN", "PENDING").contains(status) || status.contains("TRIGGER")) return "PENDING";
        return status.isEmpty() ? "UNKNOWN" : status;
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
            if (v != null && !v.toString().isEmpty()) return v.toString();
        }
        return "";
    }
}
