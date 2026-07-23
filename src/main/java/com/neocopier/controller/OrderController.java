package com.neocopier.controller;

import com.neocopier.model.Account;
import com.neocopier.model.Order;
import com.neocopier.service.AccountService;
import com.neocopier.service.TradingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final TradingService tradingService;
    private final AccountService accountService;

    public OrderController(TradingService tradingService, AccountService accountService) {
        this.tradingService = tradingService;
        this.accountService = accountService;
    }

    @GetMapping
    public List<Order> getOrders() {
        return tradingService.getAllOrders();
    }

    @DeleteMapping
    public Map<String, Boolean> clearOrders() {
        tradingService.clearOrders();
        return Map.of("success", true);
    }

    @DeleteMapping("/{order_id}")
    public Map<String, Boolean> deleteOrder(@PathVariable("order_id") String orderId) {
        tradingService.deleteOrder(orderId);
        return Map.of("success", true);
    }

    @PostMapping("/replicate")
    public ResponseEntity<Map<String, Object>> replicateOrder(@RequestBody Map<String, Object> body) {
        List<String> required = List.of("symbol", "instrument", "quantity", "transactionType", "orderType");
        if (required.stream().anyMatch(k -> !body.containsKey(k) || body.get(k) == null)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing core trading details"));
        }
        try {
            Map<String, Object> result = tradingService.replicateMasterTrade(body);
            Order masterOrder = (Order) result.get("masterOrder");
            boolean success = "SUCCESS".equalsIgnoreCase(masterOrder.getStatus()) || "PENDING".equalsIgnoreCase(masterOrder.getStatus());
            return ResponseEntity.ok(Map.of("success", success, "masterOrder", masterOrder, "slaveOrders", result.get("slaveOrders")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/sync-status")
    public Map<String, Object> syncOrderStatus() {
        List<Order> allOrders = tradingService.getAllOrders();
        List<Order> pendingOrders = allOrders.stream().filter(o -> "PENDING".equalsIgnoreCase(o.getStatus())).toList();
        if (pendingOrders.isEmpty()) {
            return Map.of("updated", 0, "orders", allOrders);
        }

        int updated = 0;
        for (Order order : pendingOrders) {
            Optional<Account> accOpt = accountService.getAccountById(order.getAccountId());
            if (accOpt.isPresent() && "active".equalsIgnoreCase(accOpt.get().getStatus())) {
                Map<String, Object> live = tradingService.getOrderLiveStatus(accOpt.get(), order.getId());
                if (live != null && !"PENDING".equalsIgnoreCase((String) live.get("status"))) {
                    order.setStatus((String) live.get("status"));
                    String msg = "FAILED".equalsIgnoreCase(order.getStatus()) ? (String) live.get("rejReason") : "CANCELLED".equalsIgnoreCase(order.getStatus()) ? (String) live.get("cancelReason") : null;
                    order.setErrorMessage(msg);
                    tradingService.getAllOrders(); // trigger update via JPA
                    updated++;
                }
            }
        }
        return Map.of("updated", updated, "orders", tradingService.getAllOrders());
    }

    @PostMapping("/{order_id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelOrder(@PathVariable("order_id") String orderId) {
        Order order = tradingService.getAllOrders().stream().filter(o -> o.getId().equals(orderId)).findFirst().orElse(null);
        if (order == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Order not found"));
        }
        if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot cancel order with status: " + order.getStatus()));
        }
        Optional<Account> accOpt = accountService.getAccountById(order.getAccountId());
        if (accOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Account not found for this order"));
        }

        Map<String, Object> res = tradingService.cancelOrderOnBroker(accOpt.get(), orderId);
        if (!Boolean.TRUE.equals(res.get("success"))) {
            return ResponseEntity.status(500).body(Map.of("error", res.getOrDefault("error", "Failed to cancel order")));
        }

        order.setStatus("CANCELLED");
        order.setErrorMessage("Cancelled by user");
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PutMapping("/{order_id}")
    public ResponseEntity<Map<String, Object>> modifyOrder(@PathVariable("order_id") String orderId, @RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> res = tradingService.modifyReplicatedTrade(orderId, body);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/margin-required")
    public Map<String, Object> getOrderMarginRequired(@RequestBody Map<String, Object> payload) {
        return tradingService.calculateOrderMargins(payload);
    }
}
