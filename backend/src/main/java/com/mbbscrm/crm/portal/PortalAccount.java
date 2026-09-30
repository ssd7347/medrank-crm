package com.mbbscrm.crm.portal;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A student's or parent's login to the self-service portal (spec 4.10). Identified by phone number. Staff
 * issue a one-time activation code; the family then chooses their own password.
 */
@Entity
@Table(name = "portal_account")
public class PortalAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String phone;
    private String displayName;
    private String passwordHash;
    private String activationCodeHash;
    private Instant activationExpiresAt;
    private boolean active = true;
    private Instant lastLoginAt;
    private Long createdBy;
    private Instant createdAt;

    protected PortalAccount() {
    }

    public PortalAccount(String phone, String displayName, Long createdBy) {
        this.phone = phone;
        this.displayName = displayName;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    /** Replaces any earlier code and clears the password, so the old password stops working too. */
    public void issueActivation(String codeHash, Instant expiresAt) {
        this.activationCodeHash = codeHash;
        this.activationExpiresAt = expiresAt;
        this.passwordHash = null;
        this.active = true;
    }

    public void activate(String passwordHash) {
        this.passwordHash = passwordHash;
        this.activationCodeHash = null;
        this.activationExpiresAt = null;
    }

    public boolean isActivated() {
        return passwordHash != null;
    }

    public Long getId() { return id; }
    public String getPhone() { return phone; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getActivationCodeHash() { return activationCodeHash; }
    public Instant getActivationExpiresAt() { return activationExpiresAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
}
