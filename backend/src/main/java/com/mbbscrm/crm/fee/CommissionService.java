package com.mbbscrm.crm.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.referral.ReferralAssociate;
import com.mbbscrm.crm.security.CurrentUser;

/**
 * Referral commissions (spec 4.8, 4.22). An entry is created automatically when a referred lead reaches
 * "Admission confirmed"; the basis is the student's consultancy fee after discount at that moment.
 */
@Service
public class CommissionService {

    private final CommissionEntryRepository commissions;
    private final FeePlanRepository plans;
    private final AuditService audit;

    public CommissionService(CommissionEntryRepository commissions, FeePlanRepository plans, AuditService audit) {
        this.commissions = commissions;
        this.plans = plans;
        this.audit = audit;
    }

    public record CommissionView(Long id, Long associateId, String associateName, Long leadId, String leadName,
                                 Long studentId, BigDecimal basisAmount, BigDecimal rate, BigDecimal amount,
                                 CommissionStatus status, Instant createdAt, LocalDate paidOn, String paidReference) {
        static CommissionView of(CommissionEntry c) {
            return new CommissionView(c.getId(), c.getAssociate().getId(), c.getAssociate().getFullName(),
                    c.getLead().getId(), c.getLead().getFullName(), c.getStudent() == null ? null : c.getStudent().getId(),
                    c.getBasisAmount(), c.getRate(), c.getAmount(), c.getStatus(), c.getCreatedAt(), c.getPaidOn(),
                    c.getPaidReference());
        }
    }

    /** Called inside the lead status change transaction. Does nothing for non-referral leads. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAdmissionConfirmed(Lead lead, Long actorId) {
        ReferralAssociate a = lead.getReferralAssociate();
        if (a == null || commissions.existsByAssociateIdAndLeadId(a.getId(), lead.getId())) {
            return;
        }
        BigDecimal basis = lead.getStudent() == null ? BigDecimal.ZERO
                : plans.findByStudentIdOrderByCreatedAtDesc(lead.getStudent().getId()).stream()
                        .filter(p -> p.getStatus() != PlanStatus.CANCELLED).map(FeePlan::netAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal amount = basis.multiply(a.getCommissionRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        CommissionEntry c = commissions.save(new CommissionEntry(a, lead, lead.getStudent(), basis,
                a.getCommissionRate(), amount));
        audit.record(actorId, "COMMISSION_CREATED", "COMMISSION", c.getId(),
                a.getFullName() + " " + a.getCommissionRate() + "% of " + basis + " = " + amount);
    }

    @Transactional(readOnly = true)
    public List<CommissionView> list(CommissionStatus status) {
        requireFees();
        return (status == null ? commissions.findAllByOrderByCreatedAtDesc()
                : commissions.findByStatusOrderByCreatedAtDesc(status)).stream().map(CommissionView::of).toList();
    }

    @Transactional
    public CommissionView approve(Long id) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can approve commissions");
        }
        CommissionEntry c = commissions.findById(id).orElseThrow(() -> ApiException.notFound("Commission"));
        if (c.getStatus() != CommissionStatus.PENDING) {
            throw ApiException.conflict("Only pending commissions can be approved");
        }
        c.setStatus(CommissionStatus.APPROVED);
        audit.record(me.id(), "COMMISSION_APPROVED", "COMMISSION", c.getId(), c.getAmount().toString());
        return CommissionView.of(c);
    }

    @Transactional
    public CommissionView cancel(Long id) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can cancel commissions");
        }
        CommissionEntry c = commissions.findById(id).orElseThrow(() -> ApiException.notFound("Commission"));
        if (c.getStatus() == CommissionStatus.PAID) {
            throw ApiException.conflict("A paid commission cannot be cancelled");
        }
        c.setStatus(CommissionStatus.CANCELLED);
        audit.record(me.id(), "COMMISSION_CANCELLED", "COMMISSION", c.getId(), null);
        return CommissionView.of(c);
    }

    @Transactional
    public CommissionView markPaid(Long id, FeeService.PaidOut req) {
        requireFees();
        CommissionEntry c = commissions.findById(id).orElseThrow(() -> ApiException.notFound("Commission"));
        if (c.getStatus() != CommissionStatus.APPROVED) {
            throw ApiException.badRequest("Only approved commissions can be marked paid");
        }
        c.markPaid(req.paidOn(), req.reference() == null || req.reference().isBlank() ? null : req.reference().trim());
        audit.record(CurrentUser.get().id(), "COMMISSION_PAID", "COMMISSION", c.getId(), c.getAmount() + " on "
                + req.paidOn());
        return CommissionView.of(c);
    }

    private static void requireFees() {
        if (!FeeService.FULL_ACCESS.contains(CurrentUser.get().role())) {
            throw ApiException.forbidden("Only accounts staff and admins can manage commissions");
        }
    }
}
