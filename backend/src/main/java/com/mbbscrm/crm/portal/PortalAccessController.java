package com.mbbscrm.crm.portal;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.portal.PortalAccessService.AccessRow;
import com.mbbscrm.crm.portal.PortalAccessService.GrantRequest;

import jakarta.validation.Valid;

/** Staff endpoints for managing a student's portal logins. */
@RestController
@RequestMapping("/api/students/{studentId}/portal-access")
public class PortalAccessController {

    private final PortalAccessService service;

    public PortalAccessController(PortalAccessService service) {
        this.service = service;
    }

    @GetMapping
    public List<AccessRow> list(@PathVariable Long studentId) {
        return service.forStudent(studentId);
    }

    @PostMapping
    public AccessRow grant(@PathVariable Long studentId, @Valid @RequestBody GrantRequest req) {
        return service.grant(studentId, req.relation());
    }

    @PostMapping("/{accountId}/disable")
    public AccessRow disable(@PathVariable Long studentId, @PathVariable Long accountId) {
        return service.setActive(studentId, accountId, false);
    }

    @PostMapping("/{accountId}/enable")
    public AccessRow enable(@PathVariable Long studentId, @PathVariable Long accountId) {
        return service.setActive(studentId, accountId, true);
    }
}
