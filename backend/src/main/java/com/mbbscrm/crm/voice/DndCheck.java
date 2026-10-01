package com.mbbscrm.crm.voice;

import java.time.Instant;

import com.mbbscrm.crm.voice.Voice.DndResult;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A cached do-not-disturb lookup for one number, kept as a hash (spec 18.9). */
@Entity
@Table(name = "dnd_check")
public class DndCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String phoneHash;
    @Enumerated(EnumType.STRING)
    private DndResult result;
    private Instant checkedAt;
    private Instant expiresAt;

    protected DndCheck() {
    }

    public DndCheck(String phoneHash, DndResult result, Instant expiresAt) {
        this.phoneHash = phoneHash;
        this.result = result;
        this.checkedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public DndResult getResult() { return result; }
    public Instant getCheckedAt() { return checkedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
