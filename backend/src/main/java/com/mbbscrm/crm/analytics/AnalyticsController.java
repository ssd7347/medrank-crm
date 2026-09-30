package com.mbbscrm.crm.analytics;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.analytics.AnalyticsService.Overview;
import com.mbbscrm.crm.analytics.ReportService.Dataset;
import com.mbbscrm.crm.analytics.ReportService.Report;

/** Analytics and the report builder (spec 4.13). Owner/admin only: the figures span every counsellor. */
@RestController
@RequestMapping("/api/analytics")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final ReportService reports;

    public AnalyticsController(AnalyticsService analytics, ReportService reports) {
        this.analytics = analytics;
        this.reports = reports;
    }

    @GetMapping("/overview")
    public Overview overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId) {
        return analytics.overview(from, to, branchId);
    }

    @GetMapping("/reports/{dataset}")
    public Report report(@PathVariable Dataset dataset,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(required = false) Long branchId) {
        return reports.run(dataset, from, to, branchId);
    }
}
