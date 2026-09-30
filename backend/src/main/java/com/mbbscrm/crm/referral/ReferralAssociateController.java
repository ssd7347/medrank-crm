package com.mbbscrm.crm.referral;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.branch.BranchRef;
import com.mbbscrm.crm.branch.BranchRepository;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.security.CurrentUser;

import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Referral associate / sub-agent directory and performance (spec 4.11, 4.22). */
@RestController
@RequestMapping("/api/referral-associates")
public class ReferralAssociateController {

    private final ReferralAssociateRepository repository;
    private final BranchRepository branches;
    private final EntityManager em;
    private final AuditService audit;

    public ReferralAssociateController(ReferralAssociateRepository repository, BranchRepository branches,
                                       EntityManager em, AuditService audit) {
        this.repository = repository;
        this.branches = branches;
        this.em = em;
        this.audit = audit;
    }

    public record AssociateRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^[0-9+ -]{10,20}$", message = "invalid phone") String phone,
            @Size(max = 80) String district,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal commissionRate,
            boolean active,
            @Email @Size(max = 160) String email,
            @Size(max = 40) String state,
            @Size(max = 300) String territory,
            LocalDate agreementStart,
            LocalDate agreementEnd,
            @Size(max = 1000) String agreementTerms,
            Long branchId) {
    }

    public record AssociateResponse(Long id, String fullName, String phone, String district,
                                    BigDecimal commissionRate, boolean active, String email, String state,
                                    String territory, LocalDate agreementStart, LocalDate agreementEnd,
                                    String agreementTerms, BranchRef branch, boolean agreementExpired) {
        static AssociateResponse of(ReferralAssociate a) {
            return new AssociateResponse(a.getId(), a.getFullName(), a.getPhone(), a.getDistrict(),
                    a.getCommissionRate(), a.isActive(), a.getEmail(), a.getState(), a.getTerritory(),
                    a.getAgreementStart(), a.getAgreementEnd(), a.getAgreementTerms(), BranchRef.of(a.getBranch()),
                    a.getAgreementEnd() != null && a.getAgreementEnd().isBefore(LocalDate.now()));
        }
    }

    /** What an associate has brought in and what they have earned, over all time. */
    public record PerformanceRow(AssociateResponse associate, long leads, long converted, long admissions,
                                 BigDecimal commissionPending, BigDecimal commissionApproved,
                                 BigDecimal commissionPaid) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AssociateResponse> list() {
        return repository.findAllByOrderByFullName().stream().map(AssociateResponse::of).toList();
    }

    @GetMapping("/performance")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ACCOUNTANT')")
    @Transactional(readOnly = true)
    public List<PerformanceRow> performance() {
        Map<Long, long[]> leadCounts = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select l.referralAssociate.id, count(l),
                       sum(case when l.student is not null then 1 else 0 end),
                       sum(case when l.status = com.mbbscrm.crm.common.LeadStatus.ADMISSION_CONFIRMED then 1 else 0 end)
                from Lead l where l.referralAssociate is not null group by l.referralAssociate.id""", Object[].class)
                .getResultList()) {
            leadCounts.put((Long) r[0], new long[] {((Number) r[1]).longValue(), ((Number) r[2]).longValue(),
                    ((Number) r[3]).longValue()});
        }
        Map<String, BigDecimal> commission = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select c.associate.id, c.status, sum(c.amount) from CommissionEntry c
                group by c.associate.id, c.status""", Object[].class).getResultList()) {
            commission.put(r[0] + ":" + r[1], (BigDecimal) r[2]);
        }
        return repository.findAllByOrderByFullName().stream().map(a -> {
            long[] n = leadCounts.getOrDefault(a.getId(), new long[3]);
            return new PerformanceRow(AssociateResponse.of(a), n[0], n[1], n[2],
                    commission.getOrDefault(a.getId() + ":PENDING", BigDecimal.ZERO),
                    commission.getOrDefault(a.getId() + ":APPROVED", BigDecimal.ZERO),
                    commission.getOrDefault(a.getId() + ":PAID", BigDecimal.ZERO));
        }).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public AssociateResponse create(@Valid @RequestBody AssociateRequest req) {
        ReferralAssociate a = apply(new ReferralAssociate(), req);
        repository.save(a);
        audit.record(CurrentUser.get().id(), "ASSOCIATE_CREATED", "REFERRAL_ASSOCIATE", a.getId(), null);
        return AssociateResponse.of(a);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public AssociateResponse update(@PathVariable Long id, @Valid @RequestBody AssociateRequest req) {
        ReferralAssociate a = repository.findById(id).orElseThrow(() -> ApiException.notFound("Associate"));
        apply(a, req);
        audit.record(CurrentUser.get().id(), "ASSOCIATE_UPDATED", "REFERRAL_ASSOCIATE", a.getId(),
                "commissionRate=" + a.getCommissionRate() + ", active=" + a.isActive());
        return AssociateResponse.of(a);
    }

    private ReferralAssociate apply(ReferralAssociate a, AssociateRequest req) {
        if (req.agreementStart() != null && req.agreementEnd() != null
                && req.agreementEnd().isBefore(req.agreementStart())) {
            throw ApiException.badRequest("The agreement end date cannot be before its start date");
        }
        a.setFullName(req.fullName().trim());
        a.setPhone(Phones.normalize(req.phone()));
        a.setDistrict(blankToNull(req.district()));
        a.setCommissionRate(req.commissionRate());
        a.setActive(req.active());
        a.setEmail(blankToNull(req.email()));
        a.setState(blankToNull(req.state()));
        a.setTerritory(blankToNull(req.territory()));
        a.setAgreementStart(req.agreementStart());
        a.setAgreementEnd(req.agreementEnd());
        a.setAgreementTerms(blankToNull(req.agreementTerms()));
        a.setBranch(req.branchId() == null ? null : branches.findById(req.branchId())
                .orElseThrow(() -> ApiException.badRequest("Branch not found")));
        return a;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
