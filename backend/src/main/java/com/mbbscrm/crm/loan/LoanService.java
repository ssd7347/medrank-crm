package com.mbbscrm.crm.loan;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.loan.LoanApplication.Status;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Education-loan facilitation (spec 4.26). The firm refers students to lenders and tracks each application
 * against the date the money is needed by, normally the college reporting deadline; it does not lend or
 * underwrite. The loan desk and admins work on every student, counsellors on their own.
 */
@Service
public class LoanService {

    static final Set<Role> FULL_ACCESS = EnumSet.of(Role.SUPER_ADMIN, Role.LOAN_DESK);
    /** An application still waiting on the lender this close to the deadline is "at risk". */
    static final int RISK_DAYS = 7;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LoanPartnerRepository partners;
    private final LoanApplicationRepository applications;
    private final StudentService students;
    private final AppUserRepository users;
    private final EntityManager em;
    private final AuditService audit;

    public LoanService(LoanPartnerRepository partners, LoanApplicationRepository applications, StudentService students,
                       AppUserRepository users, EntityManager em, AuditService audit) {
        this.partners = partners;
        this.applications = applications;
        this.students = students;
        this.users = users;
        this.em = em;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ DTOs

    public record PartnerRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 200) String interestInfo,
            @DecimalMin("0") BigDecimal maxAmount,
            @Size(max = 1000) String eligibility,
            @Size(max = 120) String contactName,
            @Pattern(regexp = "^([0-9+ -]{10,20})?$", message = "invalid phone") String contactPhone,
            boolean active) {
    }

    public record PartnerView(Long id, String name, String interestInfo, BigDecimal maxAmount, String eligibility,
                              String contactName, String contactPhone, boolean active) {
        static PartnerView of(LoanPartner p) {
            return new PartnerView(p.getId(), p.getName(), p.getInterestInfo(), p.getMaxAmount(), p.getEligibility(),
                    p.getContactName(), p.getContactPhone(), p.isActive());
        }
    }

    public record LoanRequest(
            @NotNull Long partnerId,
            @NotNull @DecimalMin("1") BigDecimal amountRequested,
            @DecimalMin("0") BigDecimal amountSanctioned,
            @NotNull Status status,
            LocalDate neededBy,
            LocalDate appliedOn,
            LocalDate decidedOn,
            @Size(max = 60) String referenceNo,
            @Size(max = 120) String coApplicant,
            @Size(max = 2000) String notes,
            Long handledById) {
    }

    /** {@code risk}: NONE, AT_RISK (deadline within a week, no decision) or LATE (deadline passed). */
    public record LoanView(Long id, Long studentId, String studentName, String studentPhone, UserRef counsellor,
                           Long partnerId, String partnerName, BigDecimal amountRequested,
                           BigDecimal amountSanctioned, Status status, LocalDate neededBy, LocalDate appliedOn,
                           LocalDate decidedOn, String referenceNo, String coApplicant, String notes,
                           UserRef handledBy, String risk, Long daysLeft, Instant updatedAt) {
    }

    public record StudentLoans(List<LoanView> applications, LocalDate suggestedNeededBy) {
    }

    // ------------------------------------------------------------------ partners

    @Transactional(readOnly = true)
    public List<PartnerView> partners() {
        return partners.findAllByOrderByName().stream().map(PartnerView::of).toList();
    }

    @Transactional
    public PartnerView savePartner(Long id, PartnerRequest req) {
        CurrentUser me = requireFull();
        LoanPartner p = id == null ? new LoanPartner()
                : partners.findById(id).orElseThrow(() -> ApiException.notFound("Lender"));
        p.setName(req.name().trim());
        p.setInterestInfo(blankToNull(req.interestInfo()));
        p.setMaxAmount(req.maxAmount());
        p.setEligibility(blankToNull(req.eligibility()));
        p.setContactName(blankToNull(req.contactName()));
        p.setContactPhone(Phones.normalize(blankToNull(req.contactPhone())));
        p.setActive(req.active());
        partners.save(p);
        audit.record(me.id(), id == null ? "LOAN_PARTNER_CREATED" : "LOAN_PARTNER_UPDATED", "LOAN_PARTNER", p.getId(),
                p.getName());
        return PartnerView.of(p);
    }

    // ------------------------------------------------------------------ applications

    @Transactional(readOnly = true)
    public StudentLoans forStudent(Long studentId) {
        Student s = students.requireAccess(studentId, FULL_ACCESS, true);
        LocalDate today = LocalDate.now(IST);
        return new StudentLoans(applications.findByStudentIdOrderByCreatedAtDesc(s.getId()).stream()
                .map(a -> view(a, today)).toList(), reportingDeadline(s.getId()));
    }

    /** For callers that have already checked access themselves, such as the family portal. */
    @Transactional(readOnly = true)
    public List<LoanView> forStudentUnchecked(Long studentId) {
        LocalDate today = LocalDate.now(IST);
        return applications.findByStudentIdOrderByCreatedAtDesc(studentId).stream().map(a -> view(a, today)).toList();
    }

    @Transactional
    public LoanView create(Long studentId, LoanRequest req) {
        Student s = students.requireAccess(studentId, FULL_ACCESS, true);
        CurrentUser me = CurrentUser.get();
        LoanApplication a = new LoanApplication();
        a.setStudent(s);
        a.setCreatedBy(me.id());
        apply(a, req, me);
        if (a.getNeededBy() == null) {
            a.setNeededBy(reportingDeadline(s.getId()));
        }
        applications.save(a);
        audit.record(me.id(), "LOAN_CREATED", "LOAN_APPLICATION", a.getId(), a.getPartner().getName() + " "
                + a.getAmountRequested());
        return view(a, LocalDate.now(IST));
    }

    @Transactional
    public LoanView update(Long id, LoanRequest req) {
        LoanApplication a = applications.findById(id).orElseThrow(() -> ApiException.notFound("Loan application"));
        students.requireAccess(a.getStudent().getId(), FULL_ACCESS, true);
        CurrentUser me = CurrentUser.get();
        Status before = a.getStatus();
        apply(a, req, me);
        audit.record(me.id(), "LOAN_UPDATED", "LOAN_APPLICATION", a.getId(), before + " -> " + a.getStatus());
        return view(a, LocalDate.now(IST));
    }

    /** Applications still in progress, soonest deadline first, for the loan desk. */
    @Transactional(readOnly = true)
    public List<LoanView> desk(boolean includeDecided) {
        CurrentUser me = CurrentUser.get();
        if (!FULL_ACCESS.contains(me.role()) && me.role() != Role.COUNSELLOR) {
            throw ApiException.forbidden("You do not have access to loans");
        }
        LocalDate today = LocalDate.now(IST);
        Set<Status> statuses = includeDecided ? EnumSet.allOf(Status.class)
                : EnumSet.of(Status.DRAFT, Status.SUBMITTED, Status.DOCS_PENDING, Status.SANCTIONED);
        return applications.findByStatusInOrderByNeededByAsc(statuses).stream()
                .filter(a -> !me.outsideBranch(a.getStudent().getBranch()))
                .filter(a -> me.role() != Role.COUNSELLOR || (a.getStudent().getAssignedCounsellor() != null
                        && a.getStudent().getAssignedCounsellor().getId().equals(me.id())))
                .map(a -> view(a, today)).toList();
    }

    /** In-progress applications whose deadline is within the risk window (or already passed). */
    @Transactional(readOnly = true)
    List<LoanApplication> atRisk(LocalDate today) {
        return applications.findByStatusInOrderByNeededByAsc(EnumSet.of(Status.DRAFT, Status.SUBMITTED,
                        Status.DOCS_PENDING)).stream()
                .filter(a -> a.getNeededBy() != null && !a.getNeededBy().isAfter(today.plusDays(RISK_DAYS)))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private void apply(LoanApplication a, LoanRequest req, CurrentUser me) {
        LoanPartner partner = partners.findById(req.partnerId())
                .orElseThrow(() -> ApiException.badRequest("Lender not found"));
        if (req.status() == Status.SANCTIONED || req.status() == Status.DISBURSED) {
            if (req.amountSanctioned() == null || req.amountSanctioned().signum() <= 0) {
                throw ApiException.badRequest("Enter the amount the lender sanctioned");
            }
        }
        if (req.status() != Status.DRAFT && req.appliedOn() == null) {
            throw ApiException.badRequest("Enter the date the application was submitted to the lender");
        }
        a.setPartner(partner);
        a.setAmountRequested(req.amountRequested());
        a.setAmountSanctioned(req.amountSanctioned());
        a.setStatus(req.status());
        a.setNeededBy(req.neededBy());
        a.setAppliedOn(req.appliedOn());
        a.setDecidedOn(req.status().inProgress() ? null
                : req.decidedOn() != null ? req.decidedOn() : a.getDecidedOn() != null ? a.getDecidedOn()
                : LocalDate.now(IST));
        a.setReferenceNo(blankToNull(req.referenceNo()));
        a.setCoApplicant(blankToNull(req.coApplicant()));
        a.setNotes(blankToNull(req.notes()));
        Long handler = req.handledById() != null ? req.handledById()
                : a.getHandledBy() != null ? a.getHandledBy().getId() : me.id();
        a.setHandledBy(users.findById(handler).filter(u -> u.isActive())
                .orElseThrow(() -> ApiException.badRequest("That staff member was not found or is inactive")));
    }

    static LoanView view(LoanApplication a, LocalDate today) {
        Student s = a.getStudent();
        Long daysLeft = a.getNeededBy() == null ? null : ChronoUnit.DAYS.between(today, a.getNeededBy());
        String risk = !a.getStatus().inProgress() || daysLeft == null ? "NONE"
                : daysLeft < 0 ? "LATE" : daysLeft <= RISK_DAYS ? "AT_RISK" : "NONE";
        return new LoanView(a.getId(), s.getId(), s.getFullName(), s.getPhone(), UserRef.of(s.getAssignedCounsellor()),
                a.getPartner().getId(), a.getPartner().getName(), a.getAmountRequested(), a.getAmountSanctioned(),
                a.getStatus(), a.getNeededBy(), a.getAppliedOn(), a.getDecidedOn(), a.getReferenceNo(),
                a.getCoApplicant(), a.getNotes(), UserRef.of(a.getHandledBy()), risk, daysLeft, a.getUpdatedAt());
    }

    /** The soonest upcoming reporting deadline on a seat the student has not given up (spec 4.6). */
    private LocalDate reportingDeadline(Long studentId) {
        List<Instant> next = em.createQuery("""
                select a.decisionDeadline from AllotmentResult a
                where a.studentCounselling.student.id = :studentId and a.college is not null
                  and a.decisionDeadline > :now
                  and (a.decision is null or a.decision <> com.mbbscrm.crm.counselling.Decision.WITHDRAW)
                order by a.decisionDeadline asc""", Instant.class)
                .setParameter("studentId", studentId).setParameter("now", Instant.now()).setMaxResults(1)
                .getResultList();
        return next.isEmpty() ? null : next.get(0).atZone(IST).toLocalDate();
    }

    private static CurrentUser requireFull() {
        CurrentUser me = CurrentUser.get();
        if (!FULL_ACCESS.contains(me.role())) {
            throw ApiException.forbidden("Only the loan desk and admins can manage lenders");
        }
        return me;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
