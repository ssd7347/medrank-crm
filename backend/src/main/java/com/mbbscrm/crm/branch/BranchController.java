package com.mbbscrm.crm.branch;

import java.util.List;
import java.util.Locale;

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
import com.mbbscrm.crm.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Branch offices (spec 4.15). Everyone can read the list for labels; only the owner/admin can change it. */
@RestController
@RequestMapping("/api/branches")
public class BranchController {

    private final BranchRepository branches;
    private final AuditService audit;

    public BranchController(BranchRepository branches, AuditService audit) {
        this.branches = branches;
        this.audit = audit;
    }

    public record BranchRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{2,20}$", message = "2-20 letters, digits or hyphens") String code,
            @Size(max = 80) String city,
            @Size(max = 40) String state,
            boolean active) {
    }

    public record BranchResponse(Long id, String name, String code, String city, String state, boolean active) {
        static BranchResponse of(Branch b) {
            return new BranchResponse(b.getId(), b.getName(), b.getCode(), b.getCity(), b.getState(), b.isActive());
        }
    }

    @GetMapping
    public List<BranchResponse> list() {
        return branches.findAllByOrderByName().stream().map(BranchResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public BranchResponse create(@Valid @RequestBody BranchRequest req) {
        String code = req.code().trim().toUpperCase(Locale.ROOT);
        if (branches.existsByCodeIgnoreCase(code)) {
            throw ApiException.conflict("A branch with this code already exists");
        }
        Branch b = new Branch();
        b.setCode(code);
        apply(b, req);
        branches.save(b);
        audit.record(CurrentUser.get().id(), "BRANCH_CREATED", "BRANCH", b.getId(), code);
        return BranchResponse.of(b);
    }

    /** The code is fixed once created, because it appears in exports and reports. */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public BranchResponse update(@PathVariable Long id, @Valid @RequestBody BranchRequest req) {
        Branch b = branches.findById(id).orElseThrow(() -> ApiException.notFound("Branch"));
        apply(b, req);
        audit.record(CurrentUser.get().id(), "BRANCH_UPDATED", "BRANCH", b.getId(), "active=" + b.isActive());
        return BranchResponse.of(b);
    }

    private static void apply(Branch b, BranchRequest req) {
        b.setName(req.name().trim());
        b.setCity(req.city() == null || req.city().isBlank() ? null : req.city().trim());
        b.setState(req.state() == null || req.state().isBlank() ? null : req.state().trim());
        b.setActive(req.active());
    }
}
