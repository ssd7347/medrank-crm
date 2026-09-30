package com.mbbscrm.crm.portal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mbbscrm.crm.portal.PortalService.DocumentsView;
import com.mbbscrm.crm.portal.PortalService.Me;
import com.mbbscrm.crm.portal.PortalService.Overview;
import com.mbbscrm.crm.portal.PortalService.PasswordRequest;
import com.mbbscrm.crm.portal.PortalService.QuestionRequest;
import com.mbbscrm.crm.portal.PortalService.TicketRow;

import jakarta.validation.Valid;

/** Student & parent portal API (spec 4.10). Only portal logins can call these; see SecurityConfig. */
@RestController
@RequestMapping("/api/portal")
public class PortalController {

    private final PortalService service;

    public PortalController(PortalService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public Me me() {
        return service.me();
    }

    @GetMapping("/students/{studentId}")
    public Overview overview(@PathVariable Long studentId) {
        return service.overview(studentId);
    }

    @PostMapping("/students/{studentId}/documents/{typeId}/files")
    public DocumentsView upload(@PathVariable Long studentId, @PathVariable Long typeId,
                                @RequestPart("file") MultipartFile file) {
        return service.upload(studentId, typeId, file);
    }

    @PostMapping("/students/{studentId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketRow ask(@PathVariable Long studentId, @Valid @RequestBody QuestionRequest req) {
        return service.ask(studentId, req);
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody PasswordRequest req) {
        service.changePassword(req);
    }
}
