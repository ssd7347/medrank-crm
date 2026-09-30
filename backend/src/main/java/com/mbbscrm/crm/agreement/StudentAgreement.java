package com.mbbscrm.crm.agreement;

import java.time.Instant;

import com.mbbscrm.crm.student.Student;

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
 * An agreement issued to one student. It holds its own copy of the text, so what was signed can never change
 * when the template is edited later. Once signed it is never modified.
 */
@Entity
@Table(name = "student_agreement")
public class StudentAgreement {

    public enum Status {
        PENDING, SIGNED, CANCELLED
    }

    /**
     * How the family agreed. PORTAL_ACCEPTANCE is a typed-name acceptance inside their own portal login;
     * PAPER is a wet signature staff hold on file; AADHAAR_ESIGN is reserved for a licensed e-Sign provider.
     */
    public enum SignMethod {
        PORTAL_ACCEPTANCE, PAPER, AADHAAR_ESIGN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    private Long templateId;
    @Enumerated(EnumType.STRING)
    private AgreementTemplate.Kind kind;
    private String title;
    private String body;
    private String bodySha256;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    @Enumerated(EnumType.STRING)
    private SignMethod signMethod;
    private String signerName;
    private String signerRelation;
    private Instant signedAt;
    private String signerIp;
    private Long portalAccountId;
    private String providerRef;
    private Long issuedBy;
    private Instant issuedAt;
    private Long recordedBy;

    protected StudentAgreement() {
    }

    public StudentAgreement(Student student, AgreementTemplate template, String body, String sha256, Long issuedBy) {
        this.student = student;
        this.templateId = template.getId();
        this.kind = template.getKind();
        this.title = template.getTitle();
        this.body = body;
        this.bodySha256 = sha256;
        this.issuedBy = issuedBy;
        this.issuedAt = Instant.now();
    }

    public void sign(SignMethod method, String signerName, String relation, String ip, Long portalAccountId,
                     Long recordedBy) {
        this.status = Status.SIGNED;
        this.signMethod = method;
        this.signerName = signerName;
        this.signerRelation = relation;
        this.signedAt = Instant.now();
        this.signerIp = ip;
        this.portalAccountId = portalAccountId;
        this.recordedBy = recordedBy;
    }

    public void cancel() {
        this.status = Status.CANCELLED;
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public AgreementTemplate.Kind getKind() { return kind; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getBodySha256() { return bodySha256; }
    public Status getStatus() { return status; }
    public SignMethod getSignMethod() { return signMethod; }
    public String getSignerName() { return signerName; }
    public String getSignerRelation() { return signerRelation; }
    public Instant getSignedAt() { return signedAt; }
    public Instant getIssuedAt() { return issuedAt; }
}
