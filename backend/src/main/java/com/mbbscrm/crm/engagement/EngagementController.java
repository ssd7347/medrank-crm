package com.mbbscrm.crm.engagement;

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

import com.mbbscrm.crm.engagement.CallService.CallRequest;
import com.mbbscrm.crm.engagement.CallService.CallView;
import com.mbbscrm.crm.engagement.SessionService.CreateRequest;
import com.mbbscrm.crm.engagement.SessionService.SessionView;
import com.mbbscrm.crm.engagement.SessionService.UpdateRequest;

import jakarta.validation.Valid;

/** Counselling sessions (spec 4.19) and call logging (spec 4.21). */
@RestController
@RequestMapping("/api")
public class EngagementController {

    private final SessionService sessions;
    private final CallService calls;

    public EngagementController(SessionService sessions, CallService calls) {
        this.sessions = sessions;
        this.calls = calls;
    }

    @GetMapping("/students/{studentId}/sessions")
    public List<SessionView> sessionsFor(@PathVariable Long studentId) {
        return sessions.forStudent(studentId);
    }

    @PostMapping("/students/{studentId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView schedule(@PathVariable Long studentId, @Valid @RequestBody CreateRequest req) {
        return sessions.create(studentId, req);
    }

    @PutMapping("/sessions/{id}")
    public SessionView updateSession(@PathVariable Long id, @Valid @RequestBody UpdateRequest req) {
        return sessions.update(id, req);
    }

    @GetMapping("/sessions/mine")
    public List<SessionView> mySessions() {
        return sessions.mine();
    }

    @PostMapping("/calls")
    @ResponseStatus(HttpStatus.CREATED)
    public CallView logCall(@Valid @RequestBody CallRequest req) {
        return calls.log(req);
    }

    @GetMapping("/leads/{leadId}/calls")
    public List<CallView> callsForLead(@PathVariable Long leadId) {
        return calls.forLead(leadId);
    }

    @GetMapping("/students/{studentId}/calls")
    public List<CallView> callsForStudent(@PathVariable Long studentId) {
        return calls.forStudent(studentId);
    }
}
