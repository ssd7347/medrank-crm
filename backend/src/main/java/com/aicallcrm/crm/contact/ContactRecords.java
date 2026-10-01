package com.aicallcrm.crm.contact;

import java.time.Instant;

import com.aicallcrm.crm.common.ActivityType;
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

// Small entities that hang off a contact, kept together because each is only a few lines.

/** One entry in a contact's timeline: a note, a call, a status change, an import... */
@Entity
@Table(name = "contact_activity")
class ContactActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long contactId;
    @Enumerated(EnumType.STRING)
    private ActivityType type;
    private String outcome;
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;
    private Instant createdAt;

    protected ContactActivity() {
    }

    ContactActivity(Long contactId, ActivityType type, String outcome, String notes, AppUser createdBy) {
        this.contactId = contactId;
        this.type = type;
        this.outcome = outcome == null || outcome.length() <= 120 ? outcome : outcome.substring(0, 120);
        this.notes = notes == null || notes.length() <= 2000 ? notes : notes.substring(0, 2000);
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    Long getId() { return id; }
    Long getContactId() { return contactId; }
    ActivityType getType() { return type; }
    String getOutcome() { return outcome; }
    String getNotes() { return notes; }
    AppUser getCreatedBy() { return createdBy; }
    Instant getCreatedAt() { return createdAt; }
}

/** A reminder for one staff member to get back to a contact. */
@Entity
@Table(name = "follow_up")
class FollowUp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id")
    private Contact contact;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private AppUser assignedTo;

    private Instant dueAt;
    private String purpose;
    private Instant completedAt;
    private Long completedBy;
    private Long createdBy;
    private Instant createdAt;

    protected FollowUp() {
    }

    FollowUp(Contact contact, AppUser assignedTo, Instant dueAt, String purpose, Long createdBy) {
        this.contact = contact;
        this.assignedTo = assignedTo;
        this.dueAt = dueAt;
        this.purpose = purpose;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    void complete(Long userId) {
        completedAt = Instant.now();
        completedBy = userId;
    }

    Long getId() { return id; }
    Contact getContact() { return contact; }
    AppUser getAssignedTo() { return assignedTo; }
    Instant getDueAt() { return dueAt; }
    String getPurpose() { return purpose; }
    Instant getCompletedAt() { return completedAt; }
}

/** One spreadsheet upload and what came of it. */
@Entity
@Table(name = "import_batch")
class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fileName;
    private int rowsTotal;
    private int createdCount;
    private int updatedCount;
    private int skippedCount;
    /** JSON list of "row N: reason" lines, the first few hundred only. */
    private String problems;
    /** JSON of how the columns were matched, so the next upload can start from it. */
    private String mapping;
    private Long createdBy;
    private Instant createdAt;

    protected ImportBatch() {
    }

    ImportBatch(String fileName, Long createdBy) {
        this.fileName = fileName;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    void result(int total, int created, int updated, int skipped, String problems, String mapping) {
        this.rowsTotal = total;
        this.createdCount = created;
        this.updatedCount = updated;
        this.skippedCount = skipped;
        this.problems = problems;
        this.mapping = mapping;
    }

    Long getId() { return id; }
    String getFileName() { return fileName; }
    int getRowsTotal() { return rowsTotal; }
    int getCreatedCount() { return createdCount; }
    int getUpdatedCount() { return updatedCount; }
    int getSkippedCount() { return skippedCount; }
    String getProblems() { return problems; }
    String getMapping() { return mapping; }
    Long getCreatedBy() { return createdBy; }
    Instant getCreatedAt() { return createdAt; }
}
