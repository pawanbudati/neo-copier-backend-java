package com.neocopier.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String id;

    @Column(name = "masterorderid")
    private String masterOrderId;

    @Column(name = "accountid")
    private String accountId;

    @Column(name = "accountname")
    private String accountName;

    @Column(name = "accountrole")
    private String accountRole;

    private String symbol;
    private String instrument;

    @Column(name = "optiontype")
    private String optionType;

    @Column(name = "strikeprice")
    private Double strikePrice;

    private String expiry;
    private Integer quantity;
    private Double price;

    @Column(name = "triggerprice")
    private Double triggerPrice;

    @Column(name = "ordertype")
    private String orderType;

    @Column(name = "transactiontype")
    private String transactionType;

    private String status;

    @Column(name = "errormessage", columnDefinition = "TEXT")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String errorMessage;

    private String timestamp;

    @Column(name = "issimulated")
    private Boolean isSimulated;

    public Order() {}

    public Order(String id, String masterOrderId, String accountId, String accountName, String accountRole,
                 String symbol, String instrument, String optionType, Double strikePrice, String expiry,
                 Integer quantity, Double price, Double triggerPrice, String orderType, String transactionType,
                 String status, String errorMessage, String timestamp, Boolean isSimulated) {
        this.id = id;
        this.masterOrderId = masterOrderId;
        this.accountId = accountId;
        this.accountName = accountName;
        this.accountRole = accountRole;
        this.symbol = symbol;
        this.instrument = instrument;
        this.optionType = optionType;
        this.strikePrice = strikePrice;
        this.expiry = expiry;
        this.quantity = quantity;
        this.price = price;
        this.triggerPrice = triggerPrice;
        this.orderType = orderType;
        this.transactionType = transactionType;
        this.status = status;
        this.errorMessage = errorMessage;
        this.timestamp = timestamp;
        this.isSimulated = isSimulated;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String masterOrderId;
        private String accountId;
        private String accountName;
        private String accountRole;
        private String symbol;
        private String instrument;
        private String optionType;
        private Double strikePrice;
        private String expiry;
        private Integer quantity;
        private Double price;
        private Double triggerPrice;
        private String orderType;
        private String transactionType;
        private String status;
        private String errorMessage;
        private String timestamp;
        private Boolean isSimulated;

        public Builder id(String id) { this.id = id; return this; }
        public Builder masterOrderId(String masterOrderId) { this.masterOrderId = masterOrderId; return this; }
        public Builder accountId(String accountId) { this.accountId = accountId; return this; }
        public Builder accountName(String accountName) { this.accountName = accountName; return this; }
        public Builder accountRole(String accountRole) { this.accountRole = accountRole; return this; }
        public Builder symbol(String symbol) { this.symbol = symbol; return this; }
        public Builder instrument(String instrument) { this.instrument = instrument; return this; }
        public Builder optionType(String optionType) { this.optionType = optionType; return this; }
        public Builder strikePrice(Double strikePrice) { this.strikePrice = strikePrice; return this; }
        public Builder expiry(String expiry) { this.expiry = expiry; return this; }
        public Builder quantity(Integer quantity) { this.quantity = quantity; return this; }
        public Builder price(Double price) { this.price = price; return this; }
        public Builder triggerPrice(Double triggerPrice) { this.triggerPrice = triggerPrice; return this; }
        public Builder orderType(String orderType) { this.orderType = orderType; return this; }
        public Builder transactionType(String transactionType) { this.transactionType = transactionType; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public Builder timestamp(String timestamp) { this.timestamp = timestamp; return this; }
        public Builder isSimulated(Boolean isSimulated) { this.isSimulated = isSimulated; return this; }

        public Order build() {
            return new Order(id, masterOrderId, accountId, accountName, accountRole, symbol, instrument,
                    optionType, strikePrice, expiry, quantity, price, triggerPrice, orderType,
                    transactionType, status, errorMessage, timestamp, isSimulated);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMasterOrderId() { return masterOrderId; }
    public void setMasterOrderId(String masterOrderId) { this.masterOrderId = masterOrderId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }

    public String getAccountRole() { return accountRole; }
    public void setAccountRole(String accountRole) { this.accountRole = accountRole; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getInstrument() { return instrument; }
    public void setInstrument(String instrument) { this.instrument = instrument; }

    public String getOptionType() { return optionType; }
    public void setOptionType(String optionType) { this.optionType = optionType; }

    public Double getStrikePrice() { return strikePrice; }
    public void setStrikePrice(Double strikePrice) { this.strikePrice = strikePrice; }

    public String getExpiry() { return expiry; }
    public void setExpiry(String expiry) { this.expiry = expiry; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public Double getTriggerPrice() { return triggerPrice; }
    public void setTriggerPrice(Double triggerPrice) { this.triggerPrice = triggerPrice; }

    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public Boolean getIsSimulated() { return isSimulated; }
    public void setIsSimulated(Boolean isSimulated) { this.isSimulated = isSimulated; }
}
