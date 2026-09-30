package com.mbbscrm.crm.alumni;

import java.time.Instant;

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
import jakarta.persistence.Table;

/** A quote from an admitted student or parent. Usable in marketing only with consent and admin approval. */
@Entity
@Table(name = "testimonial")
public class Testimonial {

    public enum Status {
        PENDING, APPROVED, REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alumni_id")
    private Alumni alumni;

    private String quote;
    private boolean consent;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private AppUser recordedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;
    private Instant createdAt;

    protected Testimonial() {
    }

    public Testimonial(Alumni alumni, String quote, boolean consent, AppUser recordedBy) {
        this.alumni = alumni;
        this.quote = quote;
        this.consent = consent;
        this.recordedBy = recordedBy;
        this.createdAt = Instant.now();
    }

    public void review(Status status, AppUser by) {
        this.status = status;
        this.reviewedBy = by;
    }

    public Long getId() { return id; }
    public Alumni getAlumni() { return alumni; }
    public String getQuote() { return quote; }
    public boolean isConsent() { return consent; }
    public Status getStatus() { return status; }
    public AppUser getRecordedBy() { return recordedBy; }
    public AppUser getReviewedBy() { return reviewedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
