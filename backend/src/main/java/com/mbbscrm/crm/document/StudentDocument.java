package com.mbbscrm.crm.document;

import java.time.Instant;
import java.time.LocalDate;

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

/** One checklist item for one student. Created on first change; absent means "not collected". */
@Entity
@Table(name = "student_document")
public class StudentDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_type_id")
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    private DocumentStatus status = DocumentStatus.NOT_COLLECTED;
    private LocalDate validUntil;
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by")
    private AppUser verifiedBy;
    private Instant verifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private AppUser updatedBy;
    private Instant updatedAt;

    protected StudentDocument() {
    }

    public StudentDocument(Student student, DocumentType type) {
        this.student = student;
        this.documentType = type;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void setStatus(DocumentStatus status, AppUser by) {
        this.status = status;
        this.updatedBy = by;
        if (status == DocumentStatus.VERIFIED || status == DocumentStatus.SUBMITTED) {
            if (verifiedAt == null) {
                verifiedBy = by;
                verifiedAt = Instant.now();
            }
        } else {
            verifiedBy = null;
            verifiedAt = null;
        }
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public DocumentType getDocumentType() { return documentType; }
    public DocumentStatus getStatus() { return status; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public AppUser getVerifiedBy() { return verifiedBy; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public AppUser getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(AppUser updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
