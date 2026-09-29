package com.mbbscrm.crm.helpdesk;

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

import com.mbbscrm.crm.helpdesk.TicketService.CommentRequest;
import com.mbbscrm.crm.helpdesk.TicketService.CreateRequest;
import com.mbbscrm.crm.helpdesk.TicketService.TicketView;
import com.mbbscrm.crm.helpdesk.TicketService.UpdateRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class TicketController {

    private final TicketService service;

    public TicketController(TicketService service) {
        this.service = service;
    }

    @GetMapping("/tickets")
    public List<TicketView> list(@RequestParam(defaultValue = "true") boolean openOnly,
                                 @RequestParam(defaultValue = "false") boolean mine) {
        return service.list(openOnly, mine);
    }

    @GetMapping("/students/{studentId}/tickets")
    public List<TicketView> forStudent(@PathVariable Long studentId) {
        return service.forStudent(studentId);
    }

    @GetMapping("/tickets/{id}")
    public TicketView get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView create(@Valid @RequestBody CreateRequest req) {
        return service.create(req);
    }

    @PutMapping("/tickets/{id}")
    public TicketView update(@PathVariable Long id, @Valid @RequestBody UpdateRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/tickets/{id}/comments")
    public TicketView comment(@PathVariable Long id, @Valid @RequestBody CommentRequest req) {
        return service.comment(id, req);
    }
}
