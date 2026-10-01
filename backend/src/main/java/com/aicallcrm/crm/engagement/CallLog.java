package com.aicallcrm.crm.engagement;

import java.time.Instant;

import com.aicallcrm.crm.user.AppUser;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One call to or from a contact. Staff dial from their own phone and record the outcome;
 * {@code provider}, {@code providerCallId} and {@code recordingUrl} are for a telephony provider to fill in
 * once one is connected.
 */
@Entity
@Table(name = "call_log")
public class CallLog {

    public enum Direction {
        OUTBOUND, INBOUND
    }

    public enum Outcome {
        CONNECTED, NO_ANSWER, BUSY, SWITCHED_OFF, WRONG_NUMBER, CALL_BACK_LATER;

        public boolean reached() {
            return this == CONNECTED || this == CALL_BACK_LATER;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long contactId;
    private String phone;
    @Enumerated(EnumType.STRING)
    private Direction direction;
    @Enumerated(EnumType.STRING)
    private Outcome outcome;
    private Integer durationSeconds;
    private String notes;
    private String provider;
    private String providerCallId;
    private String recordingUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "called_by")
    private AppUser calledBy;
    private Instant calledAt;

    protected CallLog() {
    }

    public CallLog(Long contactId, String phone, Direction direction, Outcome outcome,
                   Integer durationSeconds, String notes, AppUser calledBy) {
        this.contactId = contactId;
        this.phone = phone;
        this.direction = direction;
        this.outcome = outcome;
        this.durationSeconds = durationSeconds;
        this.notes = notes;
        this.calledBy = calledBy;
        this.calledAt = Instant.now();
    }

    /** A call made or answered by the AI voice agent; {@code voiceCallId} links to its transcript. */
    public static CallLog byAgent(Long contactId, String phone, Direction direction, Outcome outcome,
                                  Integer durationSeconds, String notes, String voiceCallId) {
        CallLog c = new CallLog(contactId, phone, direction, outcome, durationSeconds, notes, null);
        c.provider = AI_AGENT;
        c.providerCallId = voiceCallId;
        return c;
    }

    public static final String AI_AGENT = "AI_AGENT";

    public String getProviderCallId() { return providerCallId; }
    public Long getId() { return id; }
    public Long getContactId() { return contactId; }
    public String getPhone() { return phone; }
    public Direction getDirection() { return direction; }
    public Outcome getOutcome() { return outcome; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public String getNotes() { return notes; }
    public String getProvider() { return provider; }
    public String getRecordingUrl() { return recordingUrl; }
    public AppUser getCalledBy() { return calledBy; }
    public Instant getCalledAt() { return calledAt; }
}
