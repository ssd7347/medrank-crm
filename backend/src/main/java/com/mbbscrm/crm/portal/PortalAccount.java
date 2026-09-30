package com.mbbscrm.crm.portal;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A student's or parent's login to the self-service portal (spec 4.10), identified by mobile number. They
 * sign in on the common login page with a one-time code, so there is no password.
 */
@Entity
@Table(name = "portal_account")
public class PortalAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String phone;
    private String displayName;
    private boolean active = true;
    private Instant lastLoginAt;
    /** The staff member who gave access; null when the student registered themselves. */
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

    public Long getId() { return id; }
    public String getPhone() { return phone; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
