package com.mbbscrm.crm.fee;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.fee.CommissionService.CommissionView;
import com.mbbscrm.crm.fee.FeeService.CreatePlanRequest;
import com.mbbscrm.crm.fee.FeeService.DuesSummary;
import com.mbbscrm.crm.fee.FeeService.PaidOut;
import com.mbbscrm.crm.fee.FeeService.PaymentRequest;
import com.mbbscrm.crm.fee.FeeService.PaymentView;
import com.mbbscrm.crm.fee.FeeService.PlanView;
import com.mbbscrm.crm.fee.FeeService.ReasonRequest;
import com.mbbscrm.crm.fee.FeeService.Receipt;
import com.mbbscrm.crm.fee.FeeService.RefundDecision;
import com.mbbscrm.crm.fee.FeeService.RefundRequest;
import com.mbbscrm.crm.fee.FeeService.RefundView;
import com.mbbscrm.crm.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class FeeController {

    private final FeeService fees;
    private final CommissionService commissions;
    private final ServicePackageRepository packages;
    private final AuditService audit;

    public FeeController(FeeService fees, CommissionService commissions, ServicePackageRepository packages,
                         AuditService audit) {
        this.fees = fees;
        this.commissions = commissions;
        this.packages = packages;
        this.audit = audit;
    }

    // ---- student fees
    @GetMapping("/students/{studentId}/fees")
    public List<PlanView> plans(@PathVariable Long studentId) {
        return fees.plansFor(studentId);
    }

    @PostMapping("/students/{studentId}/fee-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanView createPlan(@PathVariable Long studentId, @Valid @RequestBody CreatePlanRequest req) {
        return fees.createPlan(studentId, req);
    }

    public record StatusRequest(@NotNull PlanStatus status) {
    }

    @PostMapping("/fee-plans/{planId}/status")
    public PlanView setStatus(@PathVariable Long planId, @Valid @RequestBody StatusRequest req) {
        return fees.setStatus(planId, req.status());
    }

    @PostMapping("/fee-plans/{planId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentView pay(@PathVariable Long planId, @Valid @RequestBody PaymentRequest req) {
        return fees.recordPayment(planId, req);
    }

    @PostMapping("/payments/{id}/void")
    public PaymentView voidPayment(@PathVariable Long id, @Valid @RequestBody ReasonRequest req) {
        return fees.voidPayment(id, req.reason());
    }

    @GetMapping("/payments/{id}/receipt")
    public Receipt receipt(@PathVariable Long id) {
        return fees.receipt(id);
    }

    // ---- refunds
    @PostMapping("/fee-plans/{planId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundView requestRefund(@PathVariable Long planId, @Valid @RequestBody RefundRequest req) {
        return fees.requestRefund(planId, req);
    }

    @GetMapping("/refunds")
    public List<RefundView> openRefunds() {
        return fees.openRefunds();
    }

    @PostMapping("/refunds/{id}/decide")
    public RefundView decide(@PathVariable Long id, @Valid @RequestBody RefundDecision req) {
        return fees.decideRefund(id, req);
    }

    @PostMapping("/refunds/{id}/paid")
    public RefundView refundPaid(@PathVariable Long id, @Valid @RequestBody PaidOut req) {
        return fees.markRefundPaid(id, req);
    }

    // ---- dues
    @GetMapping("/fees/dues")
    public DuesSummary dues() {
        return fees.dues();
    }

    // ---- commissions
    @GetMapping("/commissions")
    public List<CommissionView> commissions(@RequestParam(required = false) CommissionStatus status) {
        return commissions.list(status);
    }

    @PostMapping("/commissions/{id}/approve")
    public CommissionView approveCommission(@PathVariable Long id) {
        return commissions.approve(id);
    }

    @PostMapping("/commissions/{id}/cancel")
    public CommissionView cancelCommission(@PathVariable Long id) {
        return commissions.cancel(id);
    }

    @PostMapping("/commissions/{id}/paid")
    public CommissionView commissionPaid(@PathVariable Long id, @Valid @RequestBody PaidOut req) {
        return commissions.markPaid(id, req);
    }

    // ---- service packages
    public record PackageInstallmentInput(@NotBlank @Size(max = 120) String label,
                                          @NotNull @DecimalMin("0") BigDecimal amount,
                                          @Min(0) @Max(730) int dueOffsetDays) {
    }

    public record PackageRequest(@NotBlank @Size(max = 120) String name, @Size(max = 1000) String description,
                                 @NotNull @DecimalMin("0") BigDecimal totalAmount, boolean active,
                                 @Size(max = 24) List<@Valid PackageInstallmentInput> installments) {
    }

    public record PackageView(Long id, String name, String description, BigDecimal totalAmount, boolean active,
                              List<PackageInstallmentInput> installments) {
        static PackageView of(ServicePackage p) {
            return new PackageView(p.getId(), p.getName(), p.getDescription(), p.getTotalAmount(), p.isActive(),
                    p.getInstallments().stream().map(i -> new PackageInstallmentInput(i.getLabel(), i.getAmount(),
                            i.getDueOffsetDays())).toList());
        }
    }

    @GetMapping("/fee-packages")
    @Transactional(readOnly = true)
    public List<PackageView> packages() {
        return packages.findAllByOrderByActiveDescNameAsc().stream().map(PackageView::of).toList();
    }

    @PostMapping("/fee-packages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ACCOUNTANT')")
    @Transactional
    public PackageView createPackage(@Valid @RequestBody PackageRequest req) {
        ServicePackage p = apply(new ServicePackage(), req);
        packages.save(p);
        audit.record(CurrentUser.get().id(), "PACKAGE_CREATED", "SERVICE_PACKAGE", p.getId(), p.getName());
        return PackageView.of(p);
    }

    @PutMapping("/fee-packages/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ACCOUNTANT')")
    @Transactional
    public PackageView updatePackage(@PathVariable Long id, @Valid @RequestBody PackageRequest req) {
        ServicePackage p = packages.findById(id).orElseThrow(() -> ApiException.notFound("Package"));
        apply(p, req);
        packages.saveAndFlush(p);
        audit.record(CurrentUser.get().id(), "PACKAGE_UPDATED", "SERVICE_PACKAGE", p.getId(), p.getName());
        return PackageView.of(p);
    }

    private ServicePackage apply(ServicePackage p, PackageRequest req) {
        List<PackageInstallmentInput> inst = req.installments() == null ? List.of() : req.installments();
        BigDecimal sum = inst.stream().map(PackageInstallmentInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!inst.isEmpty() && sum.compareTo(req.totalAmount()) != 0) {
            throw ApiException.badRequest("Instalments add up to " + sum + " but the package total is "
                    + req.totalAmount());
        }
        p.setName(req.name().trim());
        p.setDescription(req.description() == null || req.description().isBlank() ? null : req.description().trim());
        p.setTotalAmount(req.totalAmount().setScale(2, RoundingMode.HALF_UP));
        p.setActive(req.active());
        p.getInstallments().clear();
        if (p.getId() != null) {
            packages.saveAndFlush(p);
        }
        int seq = 1;
        for (PackageInstallmentInput i : inst) {
            p.getInstallments().add(new PackageInstallment(p, seq++, i.label().trim(),
                    i.amount().setScale(2, RoundingMode.HALF_UP), i.dueOffsetDays()));
        }
        return p;
    }
}
