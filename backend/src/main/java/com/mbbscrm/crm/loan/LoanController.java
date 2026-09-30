package com.mbbscrm.crm.loan;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.loan.LoanService.LoanRequest;
import com.mbbscrm.crm.loan.LoanService.LoanView;
import com.mbbscrm.crm.loan.LoanService.PartnerRequest;
import com.mbbscrm.crm.loan.LoanService.PartnerView;
import com.mbbscrm.crm.loan.LoanService.StudentLoans;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class LoanController {

    private final LoanService service;

    public LoanController(LoanService service) {
        this.service = service;
    }

    @GetMapping("/loan-partners")
    public List<PartnerView> partners() {
        return service.partners();
    }

    @PostMapping("/loan-partners")
    @ResponseStatus(HttpStatus.CREATED)
    public PartnerView createPartner(@Valid @RequestBody PartnerRequest req) {
        return service.savePartner(null, req);
    }

    @PutMapping("/loan-partners/{id}")
    public PartnerView updatePartner(@PathVariable Long id, @Valid @RequestBody PartnerRequest req) {
        return service.savePartner(id, req);
    }

    @GetMapping("/loans")
    public List<LoanView> desk(@RequestParam(defaultValue = "false") boolean all) {
        return service.desk(all);
    }

    @GetMapping("/students/{studentId}/loans")
    public StudentLoans forStudent(@PathVariable Long studentId) {
        return service.forStudent(studentId);
    }

    @PostMapping("/students/{studentId}/loans")
    @ResponseStatus(HttpStatus.CREATED)
    public LoanView create(@PathVariable Long studentId, @Valid @RequestBody LoanRequest req) {
        return service.create(studentId, req);
    }

    @PutMapping("/loans/{id}")
    public LoanView update(@PathVariable Long id, @Valid @RequestBody LoanRequest req) {
        return service.update(id, req);
    }
}
