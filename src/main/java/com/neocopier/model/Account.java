package com.neocopier.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private String id;

    private String nickname;
    private String role;

    @Column(name = "mobilenumber")
    private String mobileNumber;

    private String ucc;
    private String mpin;

    @Column(name = "consumerkey", length = 4096, columnDefinition = "TEXT")
    private String consumerKey;

    @Column(name = "totpsecret", length = 4096, columnDefinition = "TEXT")
    private String totpSecret;

    private Double multiplier = 1.0;
    private String status = "disconnected";

    @Column(name = "lastlogin")
    private String lastLogin;

    @Column(name = "accesstoken", length = 4096, columnDefinition = "TEXT")
    private String accessToken;

    @Column(length = 4096, columnDefinition = "TEXT")
    private String sid;

    @Column(name = "neotoken", length = 4096, columnDefinition = "TEXT")
    private String neoToken;

    @Column(length = 4096, columnDefinition = "TEXT")
    private String rid;

    @Column(name = "hsserverid")
    private String hsServerId;

    @Column(name = "datacenter")
    private String dataCenter;

    @Column(name = "baseurl", length = 4096, columnDefinition = "TEXT")
    private String baseUrl;

    @Column(name = "errormessage", length = 4096, columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "createdat")
    private String createdAt;

    public Account() {}

    public Account(String id, String nickname, String role, String mobileNumber, String ucc, String mpin,
                   String consumerKey, String totpSecret, Double multiplier, String status, String lastLogin,
                   String accessToken, String sid, String neoToken, String rid, String hsServerId,
                   String dataCenter, String baseUrl, String errorMessage, String createdAt) {
        this.id = id;
        this.nickname = nickname;
        this.role = role;
        this.mobileNumber = mobileNumber;
        this.ucc = ucc;
        this.mpin = mpin;
        this.consumerKey = consumerKey;
        this.totpSecret = totpSecret;
        this.multiplier = multiplier != null ? multiplier : 1.0;
        this.status = status != null ? status : "disconnected";
        this.lastLogin = lastLogin;
        this.accessToken = accessToken;
        this.sid = sid;
        this.neoToken = neoToken;
        this.rid = rid;
        this.hsServerId = hsServerId;
        this.dataCenter = dataCenter;
        this.baseUrl = baseUrl;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getMobileNumber() { return mobileNumber; }
    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }

    public String getUcc() { return ucc; }
    public void setUcc(String ucc) { this.ucc = ucc; }

    public String getMpin() { return mpin; }
    public void setMpin(String mpin) { this.mpin = mpin; }

    public String getConsumerKey() { return consumerKey; }
    public void setConsumerKey(String consumerKey) { this.consumerKey = consumerKey; }

    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }

    public Double getMultiplier() { return multiplier; }
    public void setMultiplier(Double multiplier) { this.multiplier = multiplier; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getLastLogin() { return lastLogin; }
    public void setLastLogin(String lastLogin) { this.lastLogin = lastLogin; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getSid() { return sid; }
    public void setSid(String sid) { this.sid = sid; }

    public String getNeoToken() { return neoToken; }
    public void setNeoToken(String neoToken) { this.neoToken = neoToken; }

    public String getRid() { return rid; }
    public void setRid(String rid) { this.rid = rid; }

    public String getHsServerId() { return hsServerId; }
    public void setHsServerId(String hsServerId) { this.hsServerId = hsServerId; }

    public String getDataCenter() { return dataCenter; }
    public void setDataCenter(String dataCenter) { this.dataCenter = dataCenter; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
