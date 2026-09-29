package com.mbbscrm.crm.lead;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.common.PageResponse;
import com.mbbscrm.crm.lead.LeadDtos.ActivityRequest;
import com.mbbscrm.crm.lead.LeadDtos.ActivityResponse;
import com.mbbscrm.crm.lead.LeadDtos.AssignRequest;
import com.mbbscrm.crm.lead.LeadDtos.DuplicateRef;
import com.mbbscrm.crm.lead.LeadDtos.FollowUpRequest;
import com.mbbscrm.crm.lead.LeadDtos.FollowUpResponse;
import com.mbbscrm.crm.lead.LeadDtos.ImportResult;
import com.mbbscrm.crm.lead.LeadDtos.LeadListItem;
import com.mbbscrm.crm.lead.LeadDtos.LeadRequest;
import com.mbbscrm.crm.lead.LeadDtos.LeadResponse;
import com.mbbscrm.crm.lead.LeadDtos.StatusChangeRequest;
import com.mbbscrm.crm.student.StudentDtos.StudentRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class LeadController {

    private final LeadService service;
    private final LeadImportService importService;

    public LeadController(LeadService service, LeadImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @GetMapping("/leads")
    public PageResponse<LeadListItem> search(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) LeadStatus status,
                                             @RequestParam(required = false) LeadSource source,
                                             @RequestParam(required = false) Long counsellorId,
                                             @RequestParam(defaultValue = "false") boolean unassigned,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "25") int size) {
        return service.search(q, status, source, counsellorId, unassigned, page, size);
    }

    @GetMapping("/leads/duplicates")
    public List<DuplicateRef> duplicates(@RequestParam(required = false) String phone,
                                         @RequestParam(required = false) String neetRollNo) {
        return service.findDuplicates(phone, neetRollNo);
    }

    @GetMapping("/leads/{id}")
    public LeadResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/leads")
    @ResponseStatus(HttpStatus.CREATED)
    public LeadResponse create(@Valid @RequestBody LeadRequest req) {
        return service.create(req);
    }

    @PutMapping("/leads/{id}")
    public LeadResponse update(@PathVariable Long id, @Valid @RequestBody LeadRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/leads/{id}/status")
    public LeadResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest req) {
        return service.changeStatus(id, req.status(), req.note());
    }

    @PostMapping("/leads/{id}/assign")
    public LeadResponse assign(@PathVariable Long id, @RequestBody AssignRequest req) {
        return service.assign(id, req.userId());
    }

    @PostMapping("/leads/{id}/convert")
    public LeadResponse convert(@PathVariable Long id, @Valid @RequestBody StudentRequest req) {
        return service.convertToStudent(id, req);
    }

    @GetMapping("/leads/{id}/activities")
    public List<ActivityResponse> activities(@PathVariable Long id) {
        return service.activities(id);
    }

    @PostMapping("/leads/{id}/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityResponse addActivity(@PathVariable Long id, @Valid @RequestBody ActivityRequest req) {
        return service.addActivity(id, req);
    }

    @GetMapping("/leads/{id}/follow-ups")
    public List<FollowUpResponse> followUps(@PathVariable Long id) {
        return service.followUpsForLead(id);
    }

    @PostMapping("/leads/{id}/follow-ups")
    @ResponseStatus(HttpStatus.CREATED)
    public FollowUpResponse addFollowUp(@PathVariable Long id, @Valid @RequestBody FollowUpRequest req) {
        return service.addFollowUp(id, req);
    }

    @PostMapping("/leads/import")
    public ImportResult importCsv(@RequestPart("file") MultipartFile file) {
        return importService.importCsv(file);
    }

    @GetMapping("/follow-ups/mine")
    public List<FollowUpResponse> myFollowUps(@RequestParam(defaultValue = "7") int days) {
        return service.myFollowUps(days);
    }

    @PostMapping("/follow-ups/{id}/complete")
    public FollowUpResponse complete(@PathVariable Long id) {
        return service.completeFollowUp(id);
    }
}
