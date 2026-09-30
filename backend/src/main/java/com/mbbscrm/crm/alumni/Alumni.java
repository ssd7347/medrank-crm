package com.mbbscrm.crm.alumni;

import java.time.Instant;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;
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

/** A student who has been admitted, kept as a contact for referrals and testimonials (spec 4.23). */
@Entity
@Table(name = "alumni")
public class Alumni {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    /** Set when the college is in our database; the name is always kept so old records stay readable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "college_id")
    private College college;
    private String collegeName;

    @Enumerated(EnumType.STRING)
    private Course course;
    @Enumerated(EnumType.STRING)
    private Quota quota;
    private int admissionYear;
    private boolean willingToRefer;
    private String notes;
    private Long createdBy;
    private Instant createdAt;

    protected Alumni() {
    }

    public Alumni(Student student, Long createdBy) {
        this.student = student;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public College getCollege() { return college; }
    public void setCollege(College college) { this.college = college; }
    public String getCollegeName() { return collegeName; }
    public void setCollegeName(String collegeName) { this.collegeName = collegeName; }
    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }
    public Quota getQuota() { return quota; }
    public void setQuota(Quota quota) { this.quota = quota; }
    public int getAdmissionYear() { return admissionYear; }
    public void setAdmissionYear(int admissionYear) { this.admissionYear = admissionYear; }
    public boolean isWillingToRefer() { return willingToRefer; }
    public void setWillingToRefer(boolean willingToRefer) { this.willingToRefer = willingToRefer; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
}
