package com.mbbscrm.crm.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

/**
 * Consultancy fees (spec 4.8): plans with instalments, payments with receipts, voids, and the refund workflow.
 * Accounts staff and admins record money; counsellors can see their own students' fees and request refunds.
 * Instalment status is worked out by applying all valid payments to instalments in order (oldest first).
 */
@Service
public class FeeService {

    static final Set<Role> FULL_ACCESS = EnumSet.of(Role.SUPER_ADMIN, Role.ACCOUNTANT);
    private static final Set<Role> READ_ACCESS = EnumSet.of(Role.SUPER_ADMIN, Role.ACCOUNTANT, Role.LOAN_DESK);

    private final FeePlanRepository plans;
    private final ServicePackageRepository packages;
    private final PaymentRepository payments;
    private final FeeRefundRepository refunds;
    private final StudentService students;
    private final AppUserRepository users;
    private final AuditService audit;
    private final EntityManager em;
    private final String orgName;

    public FeeService(FeePlanRepository plans, ServicePackageRepository packages, PaymentRepository payments,
                      FeeRefundRepository refunds, StudentService students, AppUserRepository users,
                      AuditService audit, EntityManager em, @Value("${app.org-name}") String orgName) {
        this.plans = plans;
        this.packages = packages;
        this.payments = payments;
        this.refunds = refunds;
        this.students = students;
        this.users = users;
        this.audit = audit;
        this.em = em;
        this.orgName = orgName;
    }

    // ================================================================== DTOs

    public record InstallmentInput(@NotBlank @Size(max = 120) String label,
                                   @NotNull @DecimalMin("0") BigDecimal amount, @NotNull LocalDate dueDate) {
    }

    public record CreatePlanRequest(Long packageId, @Size(max = 120) String name, @DecimalMin("0") BigDecimal totalAmount,
                                    @DecimalMin("0") BigDecimal discount, LocalDate startDate,
                                    @Size(max = 24) List<@Valid InstallmentInput> installments,
                                    @Size(max = 1000) String notes) {
    }

    public record PaymentRequest(Long installmentId, @NotNull @DecimalMin("0.01") BigDecimal amount,
                                 @NotNull PaymentMethod method, @Size(max = 100) String reference,
                                 @NotNull @PastOrPresent LocalDate paidOn, @Size(max = 500) String notes) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record RefundRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank @Size(max = 1000) String reason) {
    }

    public record RefundDecision(boolean approve, @Size(max = 500) String note) {
    }

    public record PaidOut(@NotNull LocalDate paidOn, @Size(max = 100) String reference) {
    }

    public record InstallmentView(Long id, int seq, String label, BigDecimal amount, LocalDate dueDate, BigDecimal paid,
                                  BigDecimal balance, String state) {
    }

    public record PaymentView(Long id, String receiptNo, BigDecimal amount, PaymentMethod method, String reference,
                              LocalDate paidOn, String notes, UserRef receivedBy, boolean voided, String voidReason) {
        static PaymentView of(Payment p) {
            return new PaymentView(p.getId(), p.getReceiptNo(), p.getAmount(), p.getMethod(), p.getReference(),
                    p.getPaidOn(), p.getNotes(), UserRef.of(p.getReceivedBy()), p.isVoided(), p.getVoidReason());
        }
    }

    public record RefundView(Long id, Long planId, BigDecimal amount, String reason, RefundStatus status,
                             UserRef requestedBy, Instant requestedAt, UserRef decidedBy, Instant decidedAt,
                             String decisionNote, LocalDate paidOn, String paidReference) {
        static RefundView of(FeeRefund r) {
            return new RefundView(r.getId(), r.getPlan().getId(), r.getAmount(), r.getReason(), r.getStatus(),
                    UserRef.of(r.getRequestedBy()), r.getRequestedAt(), UserRef.of(r.getDecidedBy()), r.getDecidedAt(),
                    r.getDecisionNote(), r.getPaidOn(), r.getPaidReference());
        }
    }

    public record PlanView(Long id, String name, String packageName, PlanStatus status, BigDecimal totalAmount,
                           BigDecimal discount, BigDecimal netAmount, BigDecimal paid, BigDecimal refunded,
                           BigDecimal balance, String notes, Instant createdAt, List<InstallmentView> installments,
                           List<PaymentView> payments, List<RefundView> refunds) {
    }

    public record Receipt(String orgName, String receiptNo, LocalDate paidOn, BigDecimal amount, PaymentMethod method,
                          String reference, String studentName, String studentPhone, String planName,
                          BigDecimal planNet, BigDecimal paidToDate, BigDecimal balance, String receivedBy,
                          boolean voided, String voidReason) {
    }

    // ================================================================== reading

    @Transactional(readOnly = true)
    public List<PlanView> plansFor(Long studentId) {
        students.requireAccess(studentId, READ_ACCESS, true);
        return views(plans.findByStudentIdOrderByCreatedAtDesc(studentId));
    }

    /** For callers that have already checked access themselves, such as the family portal. */
    @Transactional(readOnly = true)
    public List<PlanView> plansForStudent(Student student) {
        return views(plans.findByStudentIdOrderByCreatedAtDesc(student.getId()));
    }

    List<PlanView> views(List<FeePlan> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(FeePlan::getId).toList();
        Map<Long, List<Payment>> pay = new HashMap<>();
        payments.findByPlanIdInOrderByPaidOnAscCreatedAtAsc(ids)
                .forEach(p -> pay.computeIfAbsent(p.getPlan().getId(), k -> new ArrayList<>()).add(p));
        Map<Long, List<FeeRefund>> ref = new HashMap<>();
        refunds.findByPlanIdInOrderByRequestedAtDesc(ids)
                .forEach(r -> ref.computeIfAbsent(r.getPlan().getId(), k -> new ArrayList<>()).add(r));
        return list.stream().map(p -> view(p, pay.getOrDefault(p.getId(), List.of()),
                ref.getOrDefault(p.getId(), List.of()))).toList();
    }

    static PlanView view(FeePlan p, List<Payment> pays, List<FeeRefund> refs) {
        BigDecimal paid = pays.stream().filter(x -> !x.isVoided()).map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refunded = refs.stream().filter(r -> r.getStatus() == RefundStatus.PAID).map(FeeRefund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PlanView(p.getId(), p.getName(), p.getServicePackage() == null ? null : p.getServicePackage().getName(),
                p.getStatus(), p.getTotalAmount(), p.getDiscount(), p.netAmount(), paid, refunded,
                p.netAmount().subtract(paid).max(BigDecimal.ZERO), p.getNotes(), p.getCreatedAt(),
                allocate(p.getInstallments(), paid, LocalDate.now()),
                pays.stream().map(PaymentView::of).toList().reversed(), refs.stream().map(RefundView::of).toList());
    }

    /** Applies the total paid to instalments in order. Package-private for tests and the reminder job. */
    static List<InstallmentView> allocate(List<FeeInstallment> installments, BigDecimal totalPaid, LocalDate today) {
        BigDecimal remaining = totalPaid;
        List<InstallmentView> out = new ArrayList<>();
        for (FeeInstallment i : installments) {
            BigDecimal applied = remaining.min(i.getAmount()).max(BigDecimal.ZERO);
            remaining = remaining.subtract(applied);
            BigDecimal balance = i.getAmount().subtract(applied);
            String state = balance.signum() == 0 ? "PAID"
                    : i.getDueDate().isBefore(today) ? "OVERDUE"
                    : applied.signum() > 0 ? "PARTIAL" : "DUE";
            out.add(new InstallmentView(i.getId(), i.getSeq(), i.getLabel(), i.getAmount(), i.getDueDate(), applied,
                    balance, state));
        }
        return out;
    }

    // ================================================================== plans

    @Transactional
    public PlanView createPlan(Long studentId, CreatePlanRequest req) {
        Student s = students.requireAccess(studentId, FULL_ACCESS, false);
        ServicePackage pkg = req.packageId() == null ? null : packages.findById(req.packageId())
                .filter(ServicePackage::isActive).orElseThrow(() -> ApiException.badRequest("Package not found"));
        BigDecimal total = req.totalAmount() != null ? req.totalAmount() : pkg != null ? pkg.getTotalAmount() : null;
        if (total == null) {
            throw ApiException.badRequest("Enter the total amount or choose a package");
        }
        BigDecimal discount = req.discount() == null ? BigDecimal.ZERO : req.discount();
        if (discount.compareTo(total) > 0) {
            throw ApiException.badRequest("Discount cannot exceed the total");
        }
        FeePlan plan = new FeePlan();
        plan.setStudent(s);
        plan.setServicePackage(pkg);
        plan.setName(req.name() != null && !req.name().isBlank() ? req.name().trim()
                : pkg != null ? pkg.getName() : "Consultancy fee");
        plan.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        plan.setDiscount(discount.setScale(2, RoundingMode.HALF_UP));
        plan.setNotes(req.notes() == null || req.notes().isBlank() ? null : req.notes().trim());
        plan.setCreatedBy(CurrentUser.get().id());

        List<InstallmentInput> inst = req.installments();
        if (inst == null || inst.isEmpty()) {
            inst = defaultInstallments(pkg, plan.netAmount(), req.startDate() == null ? LocalDate.now() : req.startDate());
        }
        BigDecimal sum = inst.stream().map(InstallmentInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(plan.netAmount()) != 0) {
            throw ApiException.badRequest("Instalments add up to " + sum + " but the amount due after discount is "
                    + plan.netAmount());
        }
        int seq = 1;
        for (InstallmentInput i : inst) {
            plan.getInstallments().add(new FeeInstallment(plan, seq++, i.label().trim(),
                    i.amount().setScale(2, RoundingMode.HALF_UP), i.dueDate()));
        }
        plans.save(plan);
        audit.record(CurrentUser.get().id(), "FEE_PLAN_CREATED", "FEE_PLAN", plan.getId(),
                "student=" + studentId + " net=" + plan.netAmount());
        return view(plan, List.of(), List.of());
    }

    /** Package schedule scaled to the net amount (after discount); a single instalment if no package. */
    static List<InstallmentInput> defaultInstallments(ServicePackage pkg, BigDecimal net, LocalDate start) {
        if (pkg == null || pkg.getInstallments().isEmpty() || pkg.getTotalAmount().signum() == 0) {
            return List.of(new InstallmentInput("Full payment", net, start));
        }
        List<InstallmentInput> out = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        List<PackageInstallment> list = pkg.getInstallments();
        for (int i = 0; i < list.size(); i++) {
            PackageInstallment p = list.get(i);
            BigDecimal amount = i == list.size() - 1 ? net.subtract(allocated)
                    : p.getAmount().multiply(net).divide(pkg.getTotalAmount(), 2, RoundingMode.HALF_UP);
            allocated = allocated.add(amount);
            out.add(new InstallmentInput(p.getLabel(), amount, start.plusDays(p.getDueOffsetDays())));
        }
        return out;
    }

    @Transactional
    public PlanView setStatus(Long planId, PlanStatus status) {
        FeePlan p = plans.findById(planId).orElseThrow(() -> ApiException.notFound("Fee plan"));
        students.requireAccess(p.getStudent().getId(), FULL_ACCESS, false);
        PlanStatus before = p.getStatus();
        p.setStatus(status);
        audit.record(CurrentUser.get().id(), "FEE_PLAN_STATUS", "FEE_PLAN", p.getId(), before + " -> " + status);
        return views(List.of(p)).get(0);
    }

    // ================================================================== payments

    @Transactional
    public PaymentView recordPayment(Long planId, PaymentRequest req) {
        FeePlan p = plans.findById(planId).orElseThrow(() -> ApiException.notFound("Fee plan"));
        students.requireAccess(p.getStudent().getId(), FULL_ACCESS, false);
        if (p.getStatus() != PlanStatus.ACTIVE) {
            throw ApiException.badRequest("Payments can only be recorded against an active plan");
        }
        PlanView current = views(List.of(p)).get(0);
        if (req.amount().compareTo(current.balance()) > 0) {
            throw ApiException.badRequest("Amount exceeds the balance due (" + current.balance() + ")");
        }
        if (req.installmentId() != null && p.getInstallments().stream().noneMatch(i -> i.getId().equals(req.installmentId()))) {
            throw ApiException.badRequest("That instalment belongs to another plan");
        }
        if (req.method() != PaymentMethod.CASH && (req.reference() == null || req.reference().isBlank())) {
            throw ApiException.badRequest("Enter the transaction / cheque reference for non-cash payments");
        }
        CurrentUser me = CurrentUser.get();
        Payment pay = payments.save(new Payment(p, req.installmentId(), nextReceiptNo(),
                req.amount().setScale(2, RoundingMode.HALF_UP), req.method(), blankToNull(req.reference()),
                req.paidOn(), blankToNull(req.notes()), users.getReferenceById(me.id())));
        audit.record(me.id(), "PAYMENT_RECORDED", "PAYMENT", pay.getId(), pay.getReceiptNo() + " " + pay.getAmount()
                + " " + pay.getMethod());
        return PaymentView.of(pay);
    }

    /** Admin-only correction. The payment and its receipt number stay on record, marked void. */
    @Transactional
    public PaymentView voidPayment(Long paymentId, String reason) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can void a payment");
        }
        Payment pay = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (pay.isVoided()) {
            throw ApiException.conflict("Already voided");
        }
        pay.voidPayment(reason.trim(), me.id());
        audit.record(me.id(), "PAYMENT_VOIDED", "PAYMENT", pay.getId(), pay.getReceiptNo() + ": " + reason.trim());
        return PaymentView.of(pay);
    }

    @Transactional(readOnly = true)
    public Receipt receipt(Long paymentId) {
        Payment pay = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        FeePlan p = pay.getPlan();
        Student s = students.requireAccess(p.getStudent().getId(), READ_ACCESS, true);
        BigDecimal paidToDate = payments.findByPlanIdInOrderByPaidOnAscCreatedAtAsc(List.of(p.getId())).stream()
                .filter(x -> !x.isVoided()).map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Receipt(orgName, pay.getReceiptNo(), pay.getPaidOn(), pay.getAmount(), pay.getMethod(),
                pay.getReference(), s.getFullName(), s.getPhone(), p.getName(), p.netAmount(), paidToDate,
                p.netAmount().subtract(paidToDate).max(BigDecimal.ZERO),
                pay.getReceivedBy() == null ? null : pay.getReceivedBy().getFullName(), pay.isVoided(),
                pay.getVoidReason());
    }

    private String nextReceiptNo() {
        Number n = (Number) em.createNativeQuery("select nextval('receipt_seq')").getSingleResult();
        return "RC-" + Year.now().getValue() + "-" + String.format("%06d", n.longValue());
    }

    // ================================================================== refunds

    @Transactional
    public RefundView requestRefund(Long planId, RefundRequest req) {
        FeePlan p = plans.findById(planId).orElseThrow(() -> ApiException.notFound("Fee plan"));
        students.requireAccess(p.getStudent().getId(), FULL_ACCESS, true);
        PlanView v = views(List.of(p)).get(0);
        BigDecimal committed = v.refunds().stream()
                .filter(r -> r.status() != RefundStatus.REJECTED).map(RefundView::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refundable = v.paid().subtract(committed);
        if (req.amount().compareTo(refundable) > 0) {
            throw ApiException.badRequest("At most " + refundable.max(BigDecimal.ZERO)
                    + " can be refunded (paid minus refunds already requested)");
        }
        CurrentUser me = CurrentUser.get();
        FeeRefund r = refunds.save(new FeeRefund(p, req.amount().setScale(2, RoundingMode.HALF_UP),
                req.reason().trim(), users.getReferenceById(me.id())));
        audit.record(me.id(), "REFUND_REQUESTED", "FEE_REFUND", r.getId(), r.getAmount() + ": " + r.getReason());
        return RefundView.of(r);
    }

    @Transactional
    public RefundView decideRefund(Long refundId, RefundDecision req) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can approve or reject refunds");
        }
        FeeRefund r = refunds.findById(refundId).orElseThrow(() -> ApiException.notFound("Refund"));
        if (r.getStatus() != RefundStatus.REQUESTED) {
            throw ApiException.conflict("This refund was already " + r.getStatus().name().toLowerCase());
        }
        if (!req.approve() && (req.note() == null || req.note().isBlank())) {
            throw ApiException.badRequest("Give a reason for rejecting");
        }
        r.decide(req.approve(), users.getReferenceById(me.id()), blankToNull(req.note()));
        audit.record(me.id(), req.approve() ? "REFUND_APPROVED" : "REFUND_REJECTED", "FEE_REFUND", r.getId(),
                r.getAmount() + (req.note() == null ? "" : ": " + req.note()));
        return RefundView.of(r);
    }

    @Transactional
    public RefundView markRefundPaid(Long refundId, PaidOut req) {
        FeeRefund r = refunds.findById(refundId).orElseThrow(() -> ApiException.notFound("Refund"));
        students.requireAccess(r.getPlan().getStudent().getId(), FULL_ACCESS, false);
        if (r.getStatus() != RefundStatus.APPROVED) {
            throw ApiException.badRequest("Only approved refunds can be marked paid");
        }
        r.markPaid(req.paidOn(), blankToNull(req.reference()));
        audit.record(CurrentUser.get().id(), "REFUND_PAID", "FEE_REFUND", r.getId(), r.getAmount() + " on "
                + req.paidOn());
        return RefundView.of(r);
    }

    @Transactional(readOnly = true)
    public List<RefundView> openRefunds() {
        CurrentUser me = CurrentUser.get();
        if (!FULL_ACCESS.contains(me.role())) {
            throw ApiException.forbidden("Only accounts staff and admins can see refunds");
        }
        return refunds.findByStatusInOrderByRequestedAtAsc(List.of(RefundStatus.REQUESTED, RefundStatus.APPROVED))
                .stream().filter(r -> !me.outsideBranch(r.getPlan().getStudent().getBranch()))
                .map(RefundView::of).toList();
    }

    // ================================================================== dues

    public record DueRow(Long installmentId, Long planId, Long studentId, String studentName, String studentPhone,
                         UserRef counsellor, String label, LocalDate dueDate, BigDecimal balance, long overdueDays) {
    }

    public record DuesSummary(BigDecimal outstanding, BigDecimal overdue, BigDecimal dueNext7Days,
                              BigDecimal collectedThisMonth, List<DueRow> rows) {
    }

    /** Every unpaid instalment on active plans, overdue first (spec 4.8 due reminders / overdue escalation). */
    @Transactional(readOnly = true)
    public DuesSummary dues() {
        CurrentUser me = CurrentUser.get();
        if (!READ_ACCESS.contains(me.role())) {
            throw ApiException.forbidden("Only accounts staff and admins can see dues");
        }
        return computeDues(LocalDate.now(), me.branchScope());
    }

    /** Every branch, no role check: for internal callers such as risk scoring. */
    @Transactional(readOnly = true)
    public DuesSummary duesUnchecked() {
        return computeDues(LocalDate.now(), null);
    }

    DuesSummary computeDues(LocalDate today) {
        return computeDues(today, null);
    }

    /** {@code branchId} null means every branch. */
    DuesSummary computeDues(LocalDate today, Long branchId) {
        List<FeePlan> active = plans.findByStatus(PlanStatus.ACTIVE).stream()
                .filter(p -> branchId == null || (p.getStudent().getBranch() != null
                        && branchId.equals(p.getStudent().getBranch().getId())))
                .toList();
        List<DueRow> rows = new ArrayList<>();
        BigDecimal outstanding = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        BigDecimal next7 = BigDecimal.ZERO;
        for (PlanView v : views(active)) {
            FeePlan p = active.stream().filter(x -> x.getId().equals(v.id())).findFirst().orElseThrow();
            for (InstallmentView i : v.installments()) {
                if (i.balance().signum() <= 0) {
                    continue;
                }
                long late = ChronoUnit.DAYS.between(i.dueDate(), today);
                outstanding = outstanding.add(i.balance());
                if (late > 0) {
                    overdue = overdue.add(i.balance());
                } else if (late >= -7) {
                    next7 = next7.add(i.balance());
                }
                Student s = p.getStudent();
                rows.add(new DueRow(i.id(), p.getId(), s.getId(), s.getFullName(), s.getPhone(),
                        UserRef.of(s.getAssignedCounsellor()), i.label(), i.dueDate(), i.balance(), Math.max(0, late)));
            }
        }
        rows.sort((a, b) -> a.dueDate().compareTo(b.dueDate()));
        LocalDate monthStart = today.withDayOfMonth(1);
        // Branch staff see what their branch's active plans collected this month, not the whole firm's figure.
        BigDecimal collected = branchId == null ? payments.sumCollectedBetween(monthStart, today)
                : views(active).stream().flatMap(v -> v.payments().stream())
                        .filter(x -> !x.voided() && !x.paidOn().isBefore(monthStart) && !x.paidOn().isAfter(today))
                        .map(PaymentView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DuesSummary(outstanding, overdue, next7, collected, rows);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
