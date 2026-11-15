package com.aicallcrm.crm.privacy;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The number of an erased person who had said no to AI calls, kept only as a hash. It keeps that number from
 * being called again when it comes back in an imported list; nothing else about the person is kept.
 */
@Entity
@Table(name = "erased_number")
public class ErasedNumber {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String phoneHash;
    private Instant erasedAt;

    protected ErasedNumber() {
    }

    public ErasedNumber(String phoneHash) {
        this.phoneHash = phoneHash;
        this.erasedAt = Instant.now();
    }

    public String getPhoneHash() { return phoneHash; }
    public Instant getErasedAt() { return erasedAt; }
}
