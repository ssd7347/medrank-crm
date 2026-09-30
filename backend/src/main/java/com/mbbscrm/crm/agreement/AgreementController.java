package com.mbbscrm.crm.agreement;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.agreement.AgreementService.AgreementView;
import com.mbbscrm.crm.agreement.AgreementService.PaperRequest;
import com.mbbscrm.crm.agreement.AgreementService.TemplateRequest;
import com.mbbscrm.crm.agreement.AgreementService.TemplateView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
public class AgreementController {

    private final AgreementService service;

    public AgreementController(AgreementService service) {
        this.service = service;
    }

    public record IssueRequest(@NotNull Long templateId) {
    }

    @GetMapping("/agreement-templates")
    public List<TemplateView> templates() {
        return service.templates();
    }

    @PostMapping("/agreement-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateView createTemplate(@Valid @RequestBody TemplateRequest req) {
        return service.saveTemplate(null, req);
    }

    @PutMapping("/agreement-templates/{id}")
    public TemplateView updateTemplate(@PathVariable Long id, @Valid @RequestBody TemplateRequest req) {
        return service.saveTemplate(id, req);
    }

    @GetMapping("/students/{studentId}/agreements")
    public List<AgreementView> forStudent(@PathVariable Long studentId) {
        return service.forStudent(studentId);
    }

    @PostMapping("/students/{studentId}/agreements")
    @ResponseStatus(HttpStatus.CREATED)
    public AgreementView issue(@PathVariable Long studentId, @Valid @RequestBody IssueRequest req) {
        return service.issue(studentId, req.templateId());
    }

    @GetMapping("/agreements/{id}")
    public AgreementView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/agreements/{id}/paper")
    public AgreementView recordPaper(@PathVariable Long id, @Valid @RequestBody PaperRequest req) {
        return service.recordPaper(id, req);
    }

    @PostMapping("/agreements/{id}/cancel")
    public AgreementView cancel(@PathVariable Long id) {
        return service.cancel(id);
    }
}
