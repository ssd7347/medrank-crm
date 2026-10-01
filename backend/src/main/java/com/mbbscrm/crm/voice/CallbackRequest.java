package com.mbbscrm.crm.voice;

import java.time.Instant;
import java.util.UUID;

import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.voice.Voice.CallbackStatus;

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

/** A task for a person to call someone back after the AI agent could not, or must not, help (spec 18.13). */
@Entity
@Table(name = "callback_request")
public class CallbackRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long studentId;
    private Long leadId;
    private UUID voiceCallId;
    private String phone;
    private String reason;
    private String preferredTimeText;
    private String summary;
    @Enumerated(EnumType.STRING)
    private Priority priority;
    @Enumerated(EnumType.STRING)
    private CallbackStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private AppUser assignedTo;

    private Instant dueAt;
    private Instant createdAt;
    private Instant doneAt;
    private Long doneBy;
    private String doneNote;

    protected CallbackRequest() {
    }

    public CallbackRequest(Long studentId, Long leadId, UUID voiceCallId, String phone, String reason,
                           String preferredTimeText, String summary, Priority priority, AppUser assignedTo,
                           Instant dueAt) {
        this.studentId = studentId;
        this.leadId = leadId;
        this.voiceCallId = voiceCallId;
        this.phone = phone;
        this.reason = reason;
        this.preferredTimeText = preferredTimeText;
        this.summary = summary;
        this.priority = priority;
        this.assignedTo = assignedTo;
        this.status = assignedTo == null ? CallbackStatus.OPEN : CallbackStatus.ASSIGNED;
        this.dueAt = dueAt;
        this.createdAt = Instant.now();
    }

    public void assign(AppUser user) {
        this.assignedTo = user;
        this.status = CallbackStatus.ASSIGNED;
    }

    public void complete(Long userId, String note) {
        this.status = CallbackStatus.DONE;
        this.doneAt = Instant.now();
        this.doneBy = userId;
        this.doneNote = note;
    }

    public Long getId() { return id; }
    public Long getStudentId() { return studentId; }
    public Long getLeadId() { return leadId; }
    public UUID getVoiceCallId() { return voiceCallId; }
    public String getPhone() { return phone; }
    public String getReason() { return reason; }
    public String getPreferredTimeText() { return preferredTimeText; }
    public String getSummary() { return summary; }
    public Priority getPriority() { return priority; }
    public CallbackStatus getStatus() { return status; }
    public AppUser getAssignedTo() { return assignedTo; }
    public Instant getDueAt() { return dueAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDoneAt() { return doneAt; }
    public String getDoneNote() { return doneNote; }
}
