package com.mbbscrm.crm.alumni;

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

/** A post-admission satisfaction survey, recorded by staff from a call or form (spec 4.23). */
@Entity
@Table(name = "alumni_survey")
public class AlumniSurvey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alumni_id")
    private Alumni alumni;

    private int overallRating;
    private Integer counsellorRating;
    private boolean wouldRecommend;
    private String comments;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private AppUser recordedBy;
    private Instant recordedAt;

    protected AlumniSurvey() {
    }

    public AlumniSurvey(Alumni alumni, int overallRating, Integer counsellorRating, boolean wouldRecommend,
                        String comments, AppUser recordedBy) {
        this.alumni = alumni;
        this.overallRating = overallRating;
        this.counsellorRating = counsellorRating;
        this.wouldRecommend = wouldRecommend;
        this.comments = comments;
        this.recordedBy = recordedBy;
        this.recordedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Alumni getAlumni() { return alumni; }
    public int getOverallRating() { return overallRating; }
    public Integer getCounsellorRating() { return counsellorRating; }
    public boolean isWouldRecommend() { return wouldRecommend; }
    public String getComments() { return comments; }
    public AppUser getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}
