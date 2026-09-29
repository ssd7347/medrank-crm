package com.mbbscrm.crm.predictor;

import java.time.Instant;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;
import com.mbbscrm.crm.predictor.PredictorDtos.Band;

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

/** A college option saved for a student from the predictor, to build choice lists from later. */
@Entity
@Table(name = "predictor_shortlist")
public class PredictorShortlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long studentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "college_id")
    private College college;

    @Enumerated(EnumType.STRING)
    private Course course;
    @Enumerated(EnumType.STRING)
    private Quota quota;
    @Enumerated(EnumType.STRING)
    private Band band;
    private String note;
    private Long createdBy;
    private Instant createdAt;

    protected PredictorShortlist() {
    }

    public PredictorShortlist(Long studentId, College college, Course course, Quota quota, Band band, String note,
                              Long createdBy) {
        this.studentId = studentId;
        this.college = college;
        this.course = course;
        this.quota = quota;
        this.band = band;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getStudentId() { return studentId; }
    public College getCollege() { return college; }
    public Course getCourse() { return course; }
    public Quota getQuota() { return quota; }
    public Band getBand() { return band; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
