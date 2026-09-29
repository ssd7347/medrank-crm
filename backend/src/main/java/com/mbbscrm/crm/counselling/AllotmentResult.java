package com.mbbscrm.crm.counselling;

import java.time.Instant;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;
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

/** The outcome of one round for one student's track, and the decision taken on it. No college = not allotted. */
@Entity
@Table(name = "allotment_result")
public class AllotmentResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_counselling_id")
    private StudentCounselling studentCounselling;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id")
    private CounsellingRoundEntity round;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "college_id")
    private College college;

    @Enumerated(EnumType.STRING)
    private Course course;
    @Enumerated(EnumType.STRING)
    private Quota quota;
    @Enumerated(EnumType.STRING)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private AppUser recordedBy;
    private Instant recordedAt;

    @Enumerated(EnumType.STRING)
    private Decision decision;
    private Instant decisionDeadline;
    private Instant decidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private AppUser decidedBy;
    private String decisionNote;

    public boolean isAllotted() {
        return college != null;
    }

    public void record(College college, Course course, Quota quota, Category category, AppUser by,
                       Instant deadline) {
        this.college = college;
        this.course = course;
        this.quota = quota;
        this.category = category;
        this.recordedBy = by;
        this.recordedAt = Instant.now();
        this.decisionDeadline = college == null ? null : deadline;
        this.decision = null;
        this.decidedAt = null;
        this.decidedBy = null;
        this.decisionNote = null;
    }

    public void decide(Decision decision, AppUser by, String note) {
        this.decision = decision;
        this.decidedBy = by;
        this.decidedAt = Instant.now();
        this.decisionNote = note;
    }

    public Long getId() { return id; }
    public StudentCounselling getStudentCounselling() { return studentCounselling; }
    public void setStudentCounselling(StudentCounselling sc) { this.studentCounselling = sc; }
    public CounsellingRoundEntity getRound() { return round; }
    public void setRound(CounsellingRoundEntity round) { this.round = round; }
    public College getCollege() { return college; }
    public Course getCourse() { return course; }
    public Quota getQuota() { return quota; }
    public Category getCategory() { return category; }
    public AppUser getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
    public Decision getDecision() { return decision; }
    public Instant getDecisionDeadline() { return decisionDeadline; }
    public Instant getDecidedAt() { return decidedAt; }
    public AppUser getDecidedBy() { return decidedBy; }
    public String getDecisionNote() { return decisionNote; }
}
