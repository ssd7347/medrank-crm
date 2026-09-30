package com.mbbscrm.crm.security;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A one-time sign-in code for a staff member, stored only as a hash. */
@Entity
@Table(name = "login_otp")
public class LoginOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String codeHash;
    private Instant expiresAt;
    private int attempts;
    private boolean used;
    private Instant createdAt;

    protected LoginOtp() {
    }

    public LoginOtp(Long userId, String codeHash, Instant expiresAt) {
        this.userId = userId;
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
