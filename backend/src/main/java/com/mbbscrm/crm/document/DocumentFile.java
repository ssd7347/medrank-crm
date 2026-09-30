package com.mbbscrm.crm.document;

import java.time.Instant;

import com.mbbscrm.crm.user.AppUser;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One uploaded scan. Older uploads are kept as history; the newest one is shown first. */
@Entity
@Table(name = "document_file")
public class DocumentFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_document_id")
    private StudentDocument studentDocument;

    private String storageKey;
    private String originalName;
    private String contentType;
    private long sizeBytes;
    private String sha256;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private AppUser uploadedBy;
    private Instant uploadedAt;
    /** Set instead of {@code uploadedBy} when the family uploaded the file through the portal. */
    private Long portalAccountId;

    protected DocumentFile() {
    }

    public DocumentFile(StudentDocument doc, String storageKey, String originalName, String contentType, long size,
                        String sha256, AppUser uploadedBy) {
        this.studentDocument = doc;
        this.storageKey = storageKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = size;
        this.sha256 = sha256;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getPortalAccountId() { return portalAccountId; }
    public void setPortalAccountId(Long portalAccountId) { this.portalAccountId = portalAccountId; }
    public StudentDocument getStudentDocument() { return studentDocument; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public AppUser getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}
