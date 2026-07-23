package com.neocopier.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "settings")
public class Settings {

    @Id
    private Integer id = 1;

    @Column(name = "autoreplicate")
    private Boolean autoReplicate = true;

    @Column(name = "autorenewsessions")
    private Boolean autoRenewSessions = true;

    public Settings() {}

    public Settings(Integer id, Boolean autoReplicate, Boolean autoRenewSessions) {
        this.id = id != null ? id : 1;
        this.autoReplicate = autoReplicate != null ? autoReplicate : true;
        this.autoRenewSessions = autoRenewSessions != null ? autoRenewSessions : true;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Integer id = 1;
        private Boolean autoReplicate = true;
        private Boolean autoRenewSessions = true;

        public Builder id(Integer id) { this.id = id; return this; }
        public Builder autoReplicate(Boolean autoReplicate) { this.autoReplicate = autoReplicate; return this; }
        public Builder autoRenewSessions(Boolean autoRenewSessions) { this.autoRenewSessions = autoRenewSessions; return this; }

        public Settings build() {
            return new Settings(id, autoReplicate, autoRenewSessions);
        }
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Boolean getAutoReplicate() { return autoReplicate; }
    public void setAutoReplicate(Boolean autoReplicate) { this.autoReplicate = autoReplicate; }

    public Boolean getAutoRenewSessions() { return autoRenewSessions; }
    public void setAutoRenewSessions(Boolean autoRenewSessions) { this.autoRenewSessions = autoRenewSessions; }
}
