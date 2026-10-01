package com.aicallcrm.crm.assistant;

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

import com.aicallcrm.crm.assistant.AssistantService.FaqRequest;
import com.aicallcrm.crm.assistant.AssistantService.FaqView;

import jakarta.validation.Valid;

/** The knowledge-base editor. */
@RestController
@RequestMapping("/api/faq")
public class AssistantController {

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @GetMapping
    public List<FaqView> all() {
        return service.all();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FaqView create(@Valid @RequestBody FaqRequest req) {
        return service.save(null, req);
    }

    @PutMapping("/{id}")
    public FaqView update(@PathVariable Long id, @Valid @RequestBody FaqRequest req) {
        return service.save(id, req);
    }
}
