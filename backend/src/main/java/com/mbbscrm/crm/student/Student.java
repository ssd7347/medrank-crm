package com.mbbscrm.crm.student;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.DomicileStatus;
import com.mbbscrm.crm.common.Gender;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.Nationality;
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

/** A student being actively counselled, with their NEET score/rank and eligibility inputs (spec 4.2). */
@Entity
@Table(name = "student")
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Optional national student ID; schema-ready only, no integration yet (spec 4.24). */
    private String apaarId;
    private String fullName;
    private LocalDate dateOfBirth;
    @Enumerated(EnumType.STRING)
    private Gender gender;
    private String phone;
    private String email;
    private String parentName;
    private String parentPhone;

    @Enumerated(EnumType.STRING)
    private Category category;
    private boolean pwd;
    private String homeState;
    @Enumerated(EnumType.STRING)
    private DomicileStatus domicileStatus;
    @Enumerated(EnumType.STRING)
    private Nationality nationality = Nationality.INDIAN;
    private boolean nriSponsored;

    private Integer neetYear;
    private String neetRollNo;
    private boolean neetQualified = true;
    private Integer neetScore;
    private BigDecimal neetPercentile;
    private Integer neetAir;
    private Integer categoryRank;
    private LocalDate categoryCertValidUntil;

    @Enumerated(EnumType.STRING)
    private Language languagePreference = Language.ENGLISH;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_counsellor_id")
    private AppUser assignedCounsellor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private com.mbbscrm.crm.branch.Branch branch;

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
    public String getApaarId() { return apaarId; }
    public void setApaarId(String apaarId) { this.apaarId = apaarId; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public Gender getGender() { return gender; }
    public void setGender(Gender gender) { this.gender = gender; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getParentName() { return parentName; }
    public void setParentName(String parentName) { this.parentName = parentName; }
    public String getParentPhone() { return parentPhone; }
    public void setParentPhone(String parentPhone) { this.parentPhone = parentPhone; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public boolean isPwd() { return pwd; }
    public void setPwd(boolean pwd) { this.pwd = pwd; }
    public String getHomeState() { return homeState; }
    public void setHomeState(String homeState) { this.homeState = homeState; }
    public DomicileStatus getDomicileStatus() { return domicileStatus; }
    public void setDomicileStatus(DomicileStatus domicileStatus) { this.domicileStatus = domicileStatus; }
    public Nationality getNationality() { return nationality; }
    public void setNationality(Nationality nationality) { this.nationality = nationality; }
    public boolean isNriSponsored() { return nriSponsored; }
    public void setNriSponsored(boolean nriSponsored) { this.nriSponsored = nriSponsored; }
    public Integer getNeetYear() { return neetYear; }
    public void setNeetYear(Integer neetYear) { this.neetYear = neetYear; }
    public String getNeetRollNo() { return neetRollNo; }
    public void setNeetRollNo(String neetRollNo) { this.neetRollNo = neetRollNo; }
    public boolean isNeetQualified() { return neetQualified; }
    public void setNeetQualified(boolean neetQualified) { this.neetQualified = neetQualified; }
    public Integer getNeetScore() { return neetScore; }
    public void setNeetScore(Integer neetScore) { this.neetScore = neetScore; }
    public BigDecimal getNeetPercentile() { return neetPercentile; }
    public void setNeetPercentile(BigDecimal neetPercentile) { this.neetPercentile = neetPercentile; }
    public Integer getNeetAir() { return neetAir; }
    public void setNeetAir(Integer neetAir) { this.neetAir = neetAir; }
    public Integer getCategoryRank() { return categoryRank; }
    public void setCategoryRank(Integer categoryRank) { this.categoryRank = categoryRank; }
    public LocalDate getCategoryCertValidUntil() { return categoryCertValidUntil; }
    public void setCategoryCertValidUntil(LocalDate d) { this.categoryCertValidUntil = d; }
    public Language getLanguagePreference() { return languagePreference; }
    public void setLanguagePreference(Language languagePreference) { this.languagePreference = languagePreference; }
    public AppUser getAssignedCounsellor() { return assignedCounsellor; }
    public void setAssignedCounsellor(AppUser assignedCounsellor) { this.assignedCounsellor = assignedCounsellor; }
    public com.mbbscrm.crm.branch.Branch getBranch() { return branch; }
    public void setBranch(com.mbbscrm.crm.branch.Branch branch) { this.branch = branch; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
