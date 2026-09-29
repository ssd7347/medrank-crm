package com.mbbscrm.crm.student;

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

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.PageResponse;
import com.mbbscrm.crm.student.StudentDtos.StudentListItem;
import com.mbbscrm.crm.student.StudentDtos.StudentRequest;
import com.mbbscrm.crm.student.StudentDtos.StudentResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/students")
public class StudentController {

    private final StudentService service;

    public StudentController(StudentService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<StudentListItem> search(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) Category category,
                                                @RequestParam(required = false) String homeState,
                                                @RequestParam(required = false) Long counsellorId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "25") int size) {
        return service.search(q, category, homeState, counsellorId, page, size);
    }

    @GetMapping("/{id}")
    public StudentResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StudentResponse create(@Valid @RequestBody StudentRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public StudentResponse update(@PathVariable Long id, @Valid @RequestBody StudentRequest req) {
        return service.update(id, req);
    }
}
