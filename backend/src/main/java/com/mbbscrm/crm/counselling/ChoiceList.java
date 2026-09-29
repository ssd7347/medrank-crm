package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mbbscrm.crm.user.AppUser;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * The ordered college+quota preferences for one student in one round (spec 4.5). Once locked, the exact
 * final order and who confirmed it are frozen in {@code lockedSnapshot} for dispute protection.
 */
@Entity
@Table(name = "choice_list")
public class ChoiceList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_counselling_id")
    private StudentCounselling studentCounselling;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id")
    private CounsellingRoundEntity round;

    @Enumerated(EnumType.STRING)
    private ChoiceListStatus status = ChoiceListStatus.DRAFT;

    private Instant lockedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "locked_by")
    private AppUser lockedBy;

    private String confirmedByName;
    @Enumerated(EnumType.STRING)
    private ConfirmationMethod confirmationMethod;
    private String lockedSnapshot;
    private Instant updatedAt;

    @OneToMany(mappedBy = "choiceList", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<ChoiceListItem> items = new ArrayList<>();

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void lock(AppUser by, String confirmedBy, ConfirmationMethod method, String snapshot) {
        this.status = ChoiceListStatus.LOCKED;
        this.lockedAt = Instant.now();
        this.lockedBy = by;
        this.confirmedByName = confirmedBy;
        this.confirmationMethod = method;
        this.lockedSnapshot = snapshot;
    }

    public void unlock() {
        this.status = ChoiceListStatus.DRAFT;
        this.lockedAt = null;
        this.lockedBy = null;
        this.confirmedByName = null;
        this.confirmationMethod = null;
        // lockedSnapshot is kept: the previous confirmed order stays visible in the audit log.
    }

    public Long getId() { return id; }
    public StudentCounselling getStudentCounselling() { return studentCounselling; }
    public void setStudentCounselling(StudentCounselling sc) { this.studentCounselling = sc; }
    public CounsellingRoundEntity getRound() { return round; }
    public void setRound(CounsellingRoundEntity round) { this.round = round; }
    public ChoiceListStatus getStatus() { return status; }
    public Instant getLockedAt() { return lockedAt; }
    public AppUser getLockedBy() { return lockedBy; }
    public String getConfirmedByName() { return confirmedByName; }
    public ConfirmationMethod getConfirmationMethod() { return confirmationMethod; }
    public String getLockedSnapshot() { return lockedSnapshot; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<ChoiceListItem> getItems() { return items; }
}
