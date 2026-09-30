package com.mbbscrm.crm.alumni;

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

import com.mbbscrm.crm.alumni.AlumniService.AlumniDetail;
import com.mbbscrm.crm.alumni.AlumniService.AlumniRequest;
import com.mbbscrm.crm.alumni.AlumniService.Directory;
import com.mbbscrm.crm.alumni.AlumniService.SurveyRequest;
import com.mbbscrm.crm.alumni.AlumniService.TestimonialRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class AlumniController {

    private final AlumniService service;

    public AlumniController(AlumniService service) {
        this.service = service;
    }

    @GetMapping("/alumni")
    public Directory directory(@RequestParam(required = false) String q) {
        return service.directory(q);
    }

    @GetMapping("/alumni/{id}")
    public AlumniDetail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PutMapping("/alumni/{id}")
    public AlumniDetail update(@PathVariable Long id, @Valid @RequestBody AlumniRequest req) {
        return service.update(id, req);
    }

    /** {@code alumni} is null until the student has been added to the directory. */
    public record StudentAlumni(AlumniDetail alumni) {
    }

    @GetMapping("/students/{studentId}/alumni")
    public StudentAlumni forStudent(@PathVariable Long studentId) {
        return new StudentAlumni(service.forStudent(studentId));
    }

    @PostMapping("/students/{studentId}/alumni")
    @ResponseStatus(HttpStatus.CREATED)
    public AlumniDetail create(@PathVariable Long studentId, @Valid @RequestBody AlumniRequest req) {
        return service.create(studentId, req);
    }

    @PostMapping("/alumni/{id}/surveys")
    @ResponseStatus(HttpStatus.CREATED)
    public AlumniDetail addSurvey(@PathVariable Long id, @Valid @RequestBody SurveyRequest req) {
        return service.addSurvey(id, req);
    }

    @PostMapping("/alumni/{id}/testimonials")
    @ResponseStatus(HttpStatus.CREATED)
    public AlumniDetail addTestimonial(@PathVariable Long id, @Valid @RequestBody TestimonialRequest req) {
        return service.addTestimonial(id, req);
    }

    @PostMapping("/testimonials/{id}/approve")
    public AlumniDetail approve(@PathVariable Long id) {
        return service.reviewTestimonial(id, true);
    }

    @PostMapping("/testimonials/{id}/reject")
    public AlumniDetail reject(@PathVariable Long id) {
        return service.reviewTestimonial(id, false);
    }
}
