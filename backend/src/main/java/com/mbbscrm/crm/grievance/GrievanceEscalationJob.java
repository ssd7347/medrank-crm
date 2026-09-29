package com.mbbscrm.crm.grievance;

import java.time.LocalDate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Spec 4.28: a grievance still open after its target date is escalated to the owner automatically. */
@Component
public class GrievanceEscalationJob {

    private final GrievanceRepository grievances;
    private final GrievanceService service;

    public GrievanceEscalationJob(GrievanceRepository grievances, GrievanceService service) {
        this.grievances = grievances;
        this.service = service;
    }

    @Scheduled(initialDelayString = "PT4M", fixedDelayString = "${app.alerts.scan-interval:PT10M}")
    @Transactional
    public int run() {
        int escalated = 0;
        for (Grievance g : grievances.findOverdue(LocalDate.now())) {
            if (g.getStatus() != GrievanceStatus.ESCALATED) {
                g.setStatus(GrievanceStatus.ESCALATED);
                service.trail(g, null, GrievanceActionType.ESCALATED,
                        "Automatically escalated: target resolution date " + g.getTargetResolutionDate() + " passed");
                service.escalateToOwners(g, "Target date " + g.getTargetResolutionDate() + " passed without resolution");
                escalated++;
            }
        }
        return escalated;
    }
}
