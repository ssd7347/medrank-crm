package com.mbbscrm.crm.college;

import java.time.Instant;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Historical closing rank: the last rank allotted a seat for this college/quota/category/round/year. */
@Entity
@Table(name = "cutoff_record")
public class CutoffRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long collegeId;
    @Enumerated(EnumType.STRING)
    private Course course;
    @Enumerated(EnumType.STRING)
    private Quota quota;
    @Enumerated(EnumType.STRING)
    private Category category;
    private boolean pwd;
    @Enumerated(EnumType.STRING)
    private CounsellingRound counsellingRound;
    private int academicYear;
    private int closingRank;
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getCollegeId() { return collegeId; }
    public void setCollegeId(Long collegeId) { this.collegeId = collegeId; }
    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }
    public Quota getQuota() { return quota; }
    public void setQuota(Quota quota) { this.quota = quota; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public boolean isPwd() { return pwd; }
    public void setPwd(boolean pwd) { this.pwd = pwd; }
    public CounsellingRound getCounsellingRound() { return counsellingRound; }
    public void setCounsellingRound(CounsellingRound counsellingRound) { this.counsellingRound = counsellingRound; }
    public int getAcademicYear() { return academicYear; }
    public void setAcademicYear(int academicYear) { this.academicYear = academicYear; }
    public int getClosingRank() { return closingRank; }
    public void setClosingRank(int closingRank) { this.closingRank = closingRank; }
    public Instant getUpdatedAt() { return updatedAt; }
}
