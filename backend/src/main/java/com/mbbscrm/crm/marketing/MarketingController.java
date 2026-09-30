package com.mbbscrm.crm.marketing;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.marketing.MarketingService.CampaignRequest;
import com.mbbscrm.crm.marketing.MarketingService.CampaignView;
import com.mbbscrm.crm.marketing.MarketingService.Report;
import com.mbbscrm.crm.marketing.MarketingService.SpendRequest;
import com.mbbscrm.crm.marketing.MarketingService.SpendView;

import jakarta.validation.Valid;

/** Marketing (spec 4.12). Spend and cost figures are for the owner/admin; lead forms only need the options. */
@RestController
@RequestMapping("/api/marketing")
public class MarketingController {

    private final MarketingService service;

    public MarketingController(MarketingService service) {
        this.service = service;
    }

    @GetMapping("/campaign-options")
    public List<CampaignRef> options() {
        return service.options();
    }

    @GetMapping("/report")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Report report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(required = false) Long branchId) {
        return service.report(from, to, branchId);
    }

    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CampaignView create(@Valid @RequestBody CampaignRequest req) {
        return service.create(req);
    }

    @PutMapping("/campaigns/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public CampaignView update(@PathVariable Long id, @Valid @RequestBody CampaignRequest req) {
        return service.update(id, req);
    }

    @GetMapping("/campaigns/{id}/spend")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<SpendView> spend(@PathVariable Long id) {
        return service.spendFor(id);
    }

    @PostMapping("/campaigns/{id}/spend")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public SpendView addSpend(@PathVariable Long id, @Valid @RequestBody SpendRequest req) {
        return service.addSpend(id, req);
    }

    @DeleteMapping("/spend/{spendId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void deleteSpend(@PathVariable Long spendId) {
        service.deleteSpend(spendId);
    }
}
