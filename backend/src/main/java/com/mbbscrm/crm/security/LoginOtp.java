package com.mbbscrm.crm.security;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A one-time sign-in code for a staff member or a portal (student/parent) login, stored only as a hash. */
@Entity
@Table(name = "login_otp")
public class LoginOtp {

    /** Whose code it is: a row in app_user (STAFF) or in portal_account (PORTAL). */
    public enum Subject {
        STAFF, PORTAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private Subject subjectType;
    private Long subjectId;
    private String codeHash;
    private Instant expiresAt;
    private int attempts;
    private boolean used;
    private Instant createdAt;

    protected LoginOtp() {
    }

    public LoginOtp(Subject subjectType, Long subjectId, String codeHash, Instant expiresAt) {
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public String getCodeHash() { return codeHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttempts() { return attempts; }
    public boolean isUsed() { return used; }
    public void wrongAttempt() { this.attempts++; }
    public void use() { this.used = true; }
}
