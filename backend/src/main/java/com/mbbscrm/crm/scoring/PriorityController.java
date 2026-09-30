package com.mbbscrm.crm.scoring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.scoring.PriorityService.Priorities;
import com.mbbscrm.crm.scoring.Scoring.LeadScore;
import com.mbbscrm.crm.scoring.Scoring.RiskScore;

@RestController
@RequestMapping("/api")
public class PriorityController {

    private final PriorityService service;

    public PriorityController(PriorityService service) {
        this.service = service;
    }

    @GetMapping("/priorities")
    public Priorities mine() {
        return service.mine();
    }

    @GetMapping("/leads/{id}/score")
    public LeadScore leadScore(@PathVariable Long id) {
        return service.leadScore(id);
    }

    @GetMapping("/students/{id}/risk")
    public RiskScore studentRisk(@PathVariable Long id) {
        return service.studentRisk(id);
    }
}
