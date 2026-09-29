package com.mbbscrm.crm.lead;

import java.time.Instant;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.DomicileStatus;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.referral.ReferralAssociate;
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

/** A prospective student inquiry moving through the pipeline (spec 4.1). */
@Entity
@Table(name = "lead")
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;
    private String phone;
    private String altPhone;
    private String email;
    private String neetRollNo;
    private Integer neetScore;
    private Integer neetAir;
    @Enumerated(EnumType.STRING)
    private Category category;
    private String homeState;
    @Enumerated(EnumType.STRING)
    private DomicileStatus domicileStatus;

    @Enumerated(EnumType.STRING)
    private LeadSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_associate_id")
    private ReferralAssociate referralAssociate;

    @Enumerated(EnumType.STRING)
    private LeadStatus status = LeadStatus.NEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_counsellor_id")
    private AppUser assignedCounsellor;

    @Enumerated(EnumType.STRING)
    private Language languagePreference = Language.ENGLISH;

    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAltPhone() { return altPhone; }
    public void setAltPhone(String altPhone) { this.altPhone = altPhone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getNeetRollNo() { return neetRollNo; }
    public void setNeetRollNo(String neetRollNo) { this.neetRollNo = neetRollNo; }
    public Integer getNeetScore() { return neetScore; }
    public void setNeetScore(Integer neetScore) { this.neetScore = neetScore; }
    public Integer getNeetAir() { return neetAir; }
    public void setNeetAir(Integer neetAir) { this.neetAir = neetAir; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public String getHomeState() { return homeState; }
    public void setHomeState(String homeState) { this.homeState = homeState; }
    public DomicileStatus getDomicileStatus() { return domicileStatus; }
    public void setDomicileStatus(DomicileStatus domicileStatus) { this.domicileStatus = domicileStatus; }
    public LeadSource getSource() { return source; }
    public void setSource(LeadSource source) { this.source = source; }
    public ReferralAssociate getReferralAssociate() { return referralAssociate; }
    public void setReferralAssociate(ReferralAssociate referralAssociate) { this.referralAssociate = referralAssociate; }
    public LeadStatus getStatus() { return status; }
    public void setStatus(LeadStatus status) { this.status = status; }
    public AppUser getAssignedCounsellor() { return assignedCounsellor; }
    public void setAssignedCounsellor(AppUser assignedCounsellor) { this.assignedCounsellor = assignedCounsellor; }
    public Language getLanguagePreference() { return languagePreference; }
    public void setLanguagePreference(Language languagePreference) { this.languagePreference = languagePreference; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
