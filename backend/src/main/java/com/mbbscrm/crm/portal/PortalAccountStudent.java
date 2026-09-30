package com.mbbscrm.crm.portal;

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
import jakarta.persistence.Table;

/** Which students a portal login may see, and as whom. */
@Entity
@Table(name = "portal_account_student")
public class PortalAccountStudent {

    public enum Relation {
        STUDENT, PARENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private PortalAccount account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private Student student;

    @Enumerated(EnumType.STRING)
    private Relation relation;
    private Instant createdAt;

    protected PortalAccountStudent() {
    }

    public PortalAccountStudent(PortalAccount account, Student student, Relation relation) {
        this.account = account;
        this.student = student;
        this.relation = relation;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public PortalAccount getAccount() { return account; }
    public Student getStudent() { return student; }
    public Relation getRelation() { return relation; }
}
