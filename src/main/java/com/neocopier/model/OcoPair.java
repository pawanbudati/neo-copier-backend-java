package com.neocopier.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "oco_pairs")
public class OcoPair {

    @Id
    private String id;

    @Column(name = "masterocoid")
    private String masterOcoId;

    @Column(name = "accountid")
    private String accountId;

    @Column(name = "accountname")
    private String accountName;

    private String symbol;
    private Integer quantity;

    @Column(name = "slorderid")
    private String slOrderId;

    @Column(name = "tporderid")
    private String tpOrderId;

    @Column(name = "sltriggerprice")
    private Double slTriggerPrice;

    @Column(name = "sllimitprice")
    private Double slLimitPrice;

    @Column(name = "tpprice")
    private Double tpPrice;

    @Column(name = "transactiontype")
    private String transactionType;

    private String status;
    private String timestamp;

    public OcoPair() {}

    public OcoPair(String id, String masterOcoId, String accountId, String accountName, String symbol,
                   Integer quantity, String slOrderId, String tpOrderId, Double slTriggerPrice,
                   Double slLimitPrice, Double tpPrice, String transactionType, String status, String timestamp) {
        this.id = id;
        this.masterOcoId = masterOcoId;
        this.accountId = accountId;
        this.accountName = accountName;
        this.symbol = symbol;
        this.quantity = quantity;
        this.slOrderId = slOrderId;
        this.tpOrderId = tpOrderId;
        this.slTriggerPrice = slTriggerPrice;
        this.slLimitPrice = slLimitPrice;
        this.tpPrice = tpPrice;
        this.transactionType = transactionType;
        this.status = status;
        this.timestamp = timestamp;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String masterOcoId;
        private String accountId;
        private String accountName;
        private String symbol;
        private Integer quantity;
        private String slOrderId;
        private String tpOrderId;
        private Double slTriggerPrice;
        private Double slLimitPrice;
        private Double tpPrice;
        private String transactionType;
        private String status;
        private String timestamp;

        public Builder id(String id) { this.id = id; return this; }
        public Builder masterOcoId(String masterOcoId) { this.masterOcoId = masterOcoId; return this; }
        public Builder accountId(String accountId) { this.accountId = accountId; return this; }
        public Builder accountName(String accountName) { this.accountName = accountName; return this; }
        public Builder symbol(String symbol) { this.symbol = symbol; return this; }
        public Builder quantity(Integer quantity) { this.quantity = quantity; return this; }
        public Builder slOrderId(String slOrderId) { this.slOrderId = slOrderId; return this; }
        public Builder tpOrderId(String tpOrderId) { this.tpOrderId = tpOrderId; return this; }
        public Builder slTriggerPrice(Double slTriggerPrice) { this.slTriggerPrice = slTriggerPrice; return this; }
        public Builder slLimitPrice(Double slLimitPrice) { this.slLimitPrice = slLimitPrice; return this; }
        public Builder tpPrice(Double tpPrice) { this.tpPrice = tpPrice; return this; }
        public Builder transactionType(String transactionType) { this.transactionType = transactionType; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder timestamp(String timestamp) { this.timestamp = timestamp; return this; }

        public OcoPair build() {
            return new OcoPair(id, masterOcoId, accountId, accountName, symbol, quantity, slOrderId, tpOrderId, slTriggerPrice, slLimitPrice, tpPrice, transactionType, status, timestamp);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMasterOcoId() { return masterOcoId; }
    public void setMasterOcoId(String masterOcoId) { this.masterOcoId = masterOcoId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public String getSlOrderId() { return slOrderId; }
    public void setSlOrderId(String slOrderId) { this.slOrderId = slOrderId; }

    public String getTpOrderId() { return tpOrderId; }
    public void setTpOrderId(String tpOrderId) { this.tpOrderId = tpOrderId; }

    public Double getSlTriggerPrice() { return slTriggerPrice; }
    public void setSlTriggerPrice(Double slTriggerPrice) { this.slTriggerPrice = slTriggerPrice; }

    public Double getSlLimitPrice() { return slLimitPrice; }
    public void setSlLimitPrice(Double slLimitPrice) { this.slLimitPrice = slLimitPrice; }

    public Double getTpPrice() { return tpPrice; }
    public void setTpPrice(Double tpPrice) { this.tpPrice = tpPrice; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
