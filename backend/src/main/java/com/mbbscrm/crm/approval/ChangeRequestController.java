package com.mbbscrm.crm.approval;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mbbscrm.crm.approval.ChangeRequestService.ChangeRequestDetail;
import com.mbbscrm.crm.approval.ChangeRequestService.ChangeRequestResponse;
import com.mbbscrm.crm.approval.ChangeRequestService.ReviewRequest;
import com.mbbscrm.crm.approval.ChangeRequestService.SubmitRequest;
import com.mbbscrm.crm.approval.DataChangeRequest.Action;
import com.mbbscrm.crm.approval.DataChangeRequest.EntityType;
import com.mbbscrm.crm.approval.DataChangeRequest.Status;
import com.mbbscrm.crm.common.PageResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/change-requests")
public class ChangeRequestController {

    private final ChangeRequestService service;
    private final MasterDataCsvParser csv;

    public ChangeRequestController(ChangeRequestService service, MasterDataCsvParser csv) {
        this.service = service;
        this.csv = csv;
    }

    @GetMapping
    public PageResponse<ChangeRequestResponse> list(@RequestParam(required = false) Status status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "25") int size) {
        return service.list(status, page, size);
    }

    @GetMapping("/{id}")
    public ChangeRequestDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping("/history")
    public List<ChangeRequestResponse> history(@RequestParam EntityType entityType, @RequestParam Long entityId) {
        return service.history(entityType, entityId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeRequestResponse submit(@Valid @RequestBody SubmitRequest req) {
        return service.submit(req);
    }

    @PostMapping("/bulk/seat-matrix")
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeRequestResponse bulkSeatMatrix(@RequestPart("file") MultipartFile file) {
        return service.submitPrepared(EntityType.SEAT_MATRIX, Action.BULK_UPSERT, csv.parseSeatMatrix(file));
    }

    @PostMapping("/bulk/cutoffs")
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeRequestResponse bulkCutoffs(@RequestPart("file") MultipartFile file) {
        return service.submitPrepared(EntityType.CUTOFF, Action.BULK_UPSERT, csv.parseCutoffs(file));
    }

    @PostMapping("/{id}/approve")
    public ChangeRequestResponse approve(@PathVariable Long id, @Valid @RequestBody(required = false) ReviewRequest req) {
        return service.approve(id, req == null ? null : req.note());
    }

    @PostMapping("/{id}/reject")
    public ChangeRequestResponse reject(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        return service.reject(id, req.note());
    }
}
