package com.mbbscrm.crm.counselling;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A student's track in one authority's counselling for one year. AIQ and State tracks run side by side. */
@Entity
@Table(name = "student_counselling")
public class StudentCounselling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "authority_id")
    private CounsellingAuthority authority;

    private int academicYear;
    private String registrationNo;
    @Enumerated(EnumType.STRING)
    private CounsellingStatus status = CounsellingStatus.NOT_REGISTERED;
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
    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }
    public CounsellingAuthority getAuthority() { return authority; }
    public void setAuthority(CounsellingAuthority authority) { this.authority = authority; }
    public int getAcademicYear() { return academicYear; }
    public void setAcademicYear(int academicYear) { this.academicYear = academicYear; }
    public String getRegistrationNo() { return registrationNo; }
    public void setRegistrationNo(String registrationNo) { this.registrationNo = registrationNo; }
    public CounsellingStatus getStatus() { return status; }
    public void setStatus(CounsellingStatus status) { this.status = status; }
    public Instant getUpdatedAt() { return updatedAt; }
}
