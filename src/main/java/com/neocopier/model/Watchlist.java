package com.neocopier.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "watchlist")
public class Watchlist {

    @Id
    @Column(name = "scripttoken")
    private String scriptToken;

    @Column(name = "tradingsymbol")
    private String tradingSymbol;

    private String exchange;

    @Column(name = "instrumentname")
    private String instrumentName;

    private String segment;

    @Column(name = "strikeprice")
    private Double strikePrice;

    private String expiry;

    @Column(name = "lotsize")
    private Integer lotSize;

    @Column(name = "addedat")
    private String addedAt;

    public Watchlist() {}

    public Watchlist(String scriptToken, String tradingSymbol, String exchange, String instrumentName,
                     String segment, Double strikePrice, String expiry, Integer lotSize, String addedAt) {
        this.scriptToken = scriptToken;
        this.tradingSymbol = tradingSymbol;
        this.exchange = exchange;
        this.instrumentName = instrumentName;
        this.segment = segment;
        this.strikePrice = strikePrice;
        this.expiry = expiry;
        this.lotSize = lotSize;
        this.addedAt = addedAt;
    }

    public String getScriptToken() { return scriptToken; }
    public void setScriptToken(String scriptToken) { this.scriptToken = scriptToken; }

    public String getTradingSymbol() { return tradingSymbol; }
    public void setTradingSymbol(String tradingSymbol) { this.tradingSymbol = tradingSymbol; }

    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }

    public String getInstrumentName() { return instrumentName; }
    public void setInstrumentName(String instrumentName) { this.instrumentName = instrumentName; }

    public String getSegment() { return segment; }
    public void setSegment(String segment) { this.segment = segment; }

    public Double getStrikePrice() { return strikePrice; }
    public void setStrikePrice(Double strikePrice) { this.strikePrice = strikePrice; }

    public String getExpiry() { return expiry; }
    public void setExpiry(String expiry) { this.expiry = expiry; }

    public Integer getLotSize() { return lotSize; }
    public void setLotSize(Integer lotSize) { this.lotSize = lotSize; }

    public String getAddedAt() { return addedAt; }
    public void setAddedAt(String addedAt) { this.addedAt = addedAt; }
}
