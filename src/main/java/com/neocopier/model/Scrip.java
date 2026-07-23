package com.neocopier.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "scrips")
public class Scrip {

    @Id
    @Column(name = "scripttoken")
    private String scriptToken;

    @Column(name = "tradingsymbol")
    private String tradingSymbol;

    @Column(name = "scriprefkey")
    private String scripRefKey;

    @Column(name = "instrumentname")
    private String instrumentName;

    private String exchange;
    private String segment;

    @Column(name = "strikeprice")
    private Double strikePrice;

    private LocalDate expiry;

    @Column(name = "lotsize")
    private Integer lotSize;

    public Scrip() {}

    public Scrip(String scriptToken, String tradingSymbol, String scripRefKey, String instrumentName,
                 String exchange, String segment, Double strikePrice, LocalDate expiry, Integer lotSize) {
        this.scriptToken = scriptToken;
        this.tradingSymbol = tradingSymbol;
        this.scripRefKey = scripRefKey;
        this.instrumentName = instrumentName;
        this.exchange = exchange;
        this.segment = segment;
        this.strikePrice = strikePrice;
        this.expiry = expiry;
        this.lotSize = lotSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String scriptToken;
        private String tradingSymbol;
        private String scripRefKey;
        private String instrumentName;
        private String exchange;
        private String segment;
        private Double strikePrice;
        private LocalDate expiry;
        private Integer lotSize;

        public Builder scriptToken(String scriptToken) { this.scriptToken = scriptToken; return this; }
        public Builder tradingSymbol(String tradingSymbol) { this.tradingSymbol = tradingSymbol; return this; }
        public Builder scripRefKey(String scripRefKey) { this.scripRefKey = scripRefKey; return this; }
        public Builder instrumentName(String instrumentName) { this.instrumentName = instrumentName; return this; }
        public Builder exchange(String exchange) { this.exchange = exchange; return this; }
        public Builder segment(String segment) { this.segment = segment; return this; }
        public Builder strikePrice(Double strikePrice) { this.strikePrice = strikePrice; return this; }
        public Builder expiry(LocalDate expiry) { this.expiry = expiry; return this; }
        public Builder lotSize(Integer lotSize) { this.lotSize = lotSize; return this; }

        public Scrip build() {
            return new Scrip(scriptToken, tradingSymbol, scripRefKey, instrumentName, exchange, segment, strikePrice, expiry, lotSize);
        }
    }

    public String getScriptToken() { return scriptToken; }
    public void setScriptToken(String scriptToken) { this.scriptToken = scriptToken; }

    public String getTradingSymbol() { return tradingSymbol; }
    public void setTradingSymbol(String tradingSymbol) { this.tradingSymbol = tradingSymbol; }

    public String getScripRefKey() { return scripRefKey; }
    public void setScripRefKey(String scripRefKey) { this.scripRefKey = scripRefKey; }

    public String getInstrumentName() { return instrumentName; }
    public void setInstrumentName(String instrumentName) { this.instrumentName = instrumentName; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getSegment() { return segment; }
    public void setSegment(String segment) { this.segment = segment; }

    public Double getStrikePrice() { return strikePrice; }
    public void setStrikePrice(Double strikePrice) { this.strikePrice = strikePrice; }

    public LocalDate getExpiry() { return expiry; }
    public void setExpiry(LocalDate expiry) { this.expiry = expiry; }

    public Integer getLotSize() { return lotSize; }
    public void setLotSize(Integer lotSize) { this.lotSize = lotSize; }
}
