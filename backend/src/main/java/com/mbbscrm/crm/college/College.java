package com.mbbscrm.crm.college;

import java.time.Instant;

import com.mbbscrm.crm.common.CollegeType;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** An MBBS/BDS college (spec 4.3). Only changed through an approved {@code DataChangeRequest}. */
@Entity
@Table(name = "college")
public class College {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String code;
    @Enumerated(EnumType.STRING)
    private CollegeType collegeType;
    private String state;
    private String city;
    private String affiliatedUniversity;
    private boolean nmcRecognized = true;
    private Integer establishedYear;
    private String website;
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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public CollegeType getCollegeType() { return collegeType; }
    public void setCollegeType(CollegeType collegeType) { this.collegeType = collegeType; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getAffiliatedUniversity() { return affiliatedUniversity; }
    public void setAffiliatedUniversity(String affiliatedUniversity) { this.affiliatedUniversity = affiliatedUniversity; }
    public boolean isNmcRecognized() { return nmcRecognized; }
    public void setNmcRecognized(boolean nmcRecognized) { this.nmcRecognized = nmcRecognized; }
    public Integer getEstablishedYear() { return establishedYear; }
    public void setEstablishedYear(Integer establishedYear) { this.establishedYear = establishedYear; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public Instant getUpdatedAt() { return updatedAt; }
}
