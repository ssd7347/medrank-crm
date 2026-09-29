package com.mbbscrm.crm.counselling;

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

import com.mbbscrm.crm.counselling.CounsellingDtos.AllotmentRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.AllotmentResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.BulkAllotmentResult;
import com.mbbscrm.crm.counselling.CounsellingDtos.ChoiceListResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.ChoiceListUpdateRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.DecisionPreview;
import com.mbbscrm.crm.counselling.CounsellingDtos.DecisionRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.LockRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.RoundDesk;
import com.mbbscrm.crm.counselling.CounsellingDtos.TrackRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.TrackResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.TrackUpdateRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.UnlockRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class CounsellingController {

    private final CounsellingService service;
    private final AllotmentImportService importService;
    private final DeadlineAlertJob deadlineJob;

    public CounsellingController(CounsellingService service, AllotmentImportService importService,
                                 DeadlineAlertJob deadlineJob) {
        this.service = service;
        this.importService = importService;
        this.deadlineJob = deadlineJob;
    }

    // ---- tracks
    @GetMapping("/students/{studentId}/counselling")
    public List<TrackResponse> tracks(@PathVariable Long studentId) {
        return service.tracksFor(studentId);
    }

    @PostMapping("/students/{studentId}/counselling")
    @ResponseStatus(HttpStatus.CREATED)
    public TrackResponse createTrack(@PathVariable Long studentId, @Valid @RequestBody TrackRequest req) {
        return service.createTrack(studentId, req);
    }

    @PutMapping("/counselling/tracks/{trackId}")
    public TrackResponse updateTrack(@PathVariable Long trackId, @Valid @RequestBody TrackUpdateRequest req) {
        return service.updateTrack(trackId, req);
    }

    // ---- choice lists
    @PostMapping("/counselling/tracks/{trackId}/rounds/{roundId}/choice-list")
    public ChoiceListResponse openChoiceList(@PathVariable Long trackId, @PathVariable Long roundId) {
        return service.openChoiceList(trackId, roundId);
    }

    @GetMapping("/choice-lists/{id}")
    public ChoiceListResponse choiceList(@PathVariable Long id) {
        return service.getChoiceList(id);
    }

    @PutMapping("/choice-lists/{id}/items")
    public ChoiceListResponse saveItems(@PathVariable Long id, @Valid @RequestBody ChoiceListUpdateRequest req) {
        return service.saveItems(id, req.items());
    }

    @PostMapping("/choice-lists/{id}/copy-from/{sourceId}")
    public ChoiceListResponse copyFrom(@PathVariable Long id, @PathVariable Long sourceId) {
        return service.copyFrom(id, sourceId);
    }

    @PostMapping("/choice-lists/{id}/lock")
    public ChoiceListResponse lock(@PathVariable Long id, @Valid @RequestBody LockRequest req) {
        return service.lock(id, req);
    }

    @PostMapping("/choice-lists/{id}/unlock")
    public ChoiceListResponse unlock(@PathVariable Long id, @Valid @RequestBody UnlockRequest req) {
        return service.unlock(id, req.reason());
    }

    // ---- allotments & decisions
    @PostMapping("/counselling/tracks/{trackId}/allotments")
    public AllotmentResponse recordAllotment(@PathVariable Long trackId, @Valid @RequestBody AllotmentRequest req) {
        return service.recordAllotment(trackId, req);
    }

    @GetMapping("/allotments/{id}/decision-preview")
    public DecisionPreview preview(@PathVariable Long id, @RequestParam Decision decision) {
        return service.previewDecision(id, decision);
    }

    @PostMapping("/allotments/{id}/decision")
    public AllotmentResponse decide(@PathVariable Long id, @Valid @RequestBody DecisionRequest req) {
        return service.decide(id, req);
    }

    @PostMapping("/counselling/rounds/{roundId}/allotments/import")
    public BulkAllotmentResult importAllotments(@PathVariable Long roundId, @RequestPart("file") MultipartFile file) {
        return importService.importCsv(roundId, file);
    }

    // ---- round desk
    @GetMapping("/counselling/desk")
    public RoundDesk desk() {
        return service.desk();
    }

    /** Admin "scan now" for deadline alerts, instead of waiting for the next scheduled run. */
    @PostMapping("/counselling/scan-deadlines")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPER_ADMIN')")
    public DeadlineAlertJob.ScanResult scanNow() {
        return deadlineJob.run();
    }
}
