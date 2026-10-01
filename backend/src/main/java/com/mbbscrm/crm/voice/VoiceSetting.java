package com.mbbscrm.crm.voice;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The single "Pause all" switch (spec 18.10.3). While paused, nothing is dialled. */
@Entity
@Table(name = "voice_setting")
public class VoiceSetting {

    static final int ID = 1;

    @Id
    private Integer id;

    private boolean paused;
    private Long pausedBy;
    private Instant pausedAt;

    protected VoiceSetting() {
    }

    public void set(boolean paused, Long userId) {
        this.paused = paused;
        this.pausedBy = paused ? userId : null;
        this.pausedAt = paused ? Instant.now() : null;
    }

    public boolean isPaused() { return paused; }
    public Instant getPausedAt() { return pausedAt; }
}
