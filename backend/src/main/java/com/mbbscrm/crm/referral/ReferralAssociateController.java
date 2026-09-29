package com.mbbscrm.crm.referral;

import java.math.BigDecimal;
import java.util.List;

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
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/referral-associates")
public class ReferralAssociateController {

    private final ReferralAssociateRepository repository;
    private final AuditService audit;

    public ReferralAssociateController(ReferralAssociateRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public record AssociateRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^[0-9+ -]{10,20}$", message = "invalid phone") String phone,
            @Size(max = 80) String district,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal commissionRate,
            boolean active) {
    }

    public record AssociateResponse(Long id, String fullName, String phone, String district,
                                    BigDecimal commissionRate, boolean active) {
        static AssociateResponse of(ReferralAssociate a) {
            return new AssociateResponse(a.getId(), a.getFullName(), a.getPhone(), a.getDistrict(),
                    a.getCommissionRate(), a.isActive());
        }
    }

    @GetMapping
    public List<AssociateResponse> list() {
        return repository.findAllByOrderByFullName().stream().map(AssociateResponse::of).toList();
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

    private static ReferralAssociate apply(ReferralAssociate a, AssociateRequest req) {
        a.setFullName(req.fullName().trim());
        a.setPhone(Phones.normalize(req.phone()));
        a.setDistrict(req.district() == null || req.district().isBlank() ? null : req.district().trim());
        a.setCommissionRate(req.commissionRate());
        a.setActive(req.active());
        return a;
    }
}
