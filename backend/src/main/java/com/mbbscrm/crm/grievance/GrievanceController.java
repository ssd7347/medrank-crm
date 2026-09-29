package com.mbbscrm.crm.grievance;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.grievance.GrievanceService.ActionRequest;
import com.mbbscrm.crm.grievance.GrievanceService.CreateRequest;
import com.mbbscrm.crm.grievance.GrievanceService.GrievanceView;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/grievances")
public class GrievanceController {

    private final GrievanceService service;

    public GrievanceController(GrievanceService service) {
        this.service = service;
    }

    @GetMapping
    public List<GrievanceView> list(@RequestParam(defaultValue = "true") boolean openOnly) {
        return service.list(openOnly);
    }

    @GetMapping("/{id}")
    public GrievanceView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GrievanceView create(@Valid @RequestBody CreateRequest req) {
        return service.create(req);
    }

    @PostMapping("/{id}/actions")
    public GrievanceView act(@PathVariable Long id, @Valid @RequestBody ActionRequest req) {
        return service.act(id, req);
    }
}
