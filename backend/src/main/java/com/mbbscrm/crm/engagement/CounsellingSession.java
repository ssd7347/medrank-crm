package com.mbbscrm.crm.engagement;

import java.time.Instant;

import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.user.AppUser;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A scheduled consultation with a student or parent, with its notes kept on the student record (spec 4.19). */
@Entity
@Table(name = "counselling_session")
public class CounsellingSession {

    public enum Mode {
        VIDEO, PHONE, IN_PERSON
    }

    public enum Status {
        SCHEDULED, COMPLETED, CANCELLED, NO_SHOW
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "host_id")
    private AppUser host;

    @Enumerated(EnumType.STRING)
    private Mode mode;
    private Instant scheduledAt;
    private int durationMinutes;
    private String topic;
    private String meetingUrl;
    @Enumerated(EnumType.STRING)
    private Status status = Status.SCHEDULED;
    private String notes;
    private boolean recordingConsent;
    private String recordingUrl;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }
    public AppUser getHost() { return host; }
    public void setHost(AppUser host) { this.host = host; }
    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getMeetingUrl() { return meetingUrl; }
    public void setMeetingUrl(String meetingUrl) { this.meetingUrl = meetingUrl; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isRecordingConsent() { return recordingConsent; }
    public void setRecordingConsent(boolean recordingConsent) { this.recordingConsent = recordingConsent; }
    public String getRecordingUrl() { return recordingUrl; }
    public void setRecordingUrl(String recordingUrl) { this.recordingUrl = recordingUrl; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
