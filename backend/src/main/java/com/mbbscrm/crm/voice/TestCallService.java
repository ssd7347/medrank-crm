package com.mbbscrm.crm.voice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.voice.Voice.CallStatus;
import com.mbbscrm.crm.voice.Voice.Direction;
import com.mbbscrm.crm.voice.Voice.Outcome;
import com.mbbscrm.crm.voice.Voice.Purpose;
import com.mbbscrm.crm.voice.VoiceCallService.Line;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The test console (spec 18.16, phase A "sandbox"): an admin types what a caller would say and watches the
 * agent answer, tool by tool, against a real student's record. A test call is marked as such and changes
 * nothing for the family: no callback task, no opt-out, no grievance and no entry in their call history.
 */
@Service
public class TestCallService {

    public record StartRequest(Long studentId, Long leadId, @NotNull Purpose purpose, Language language) {
    }

    public record SayRequest(@NotBlank @Size(max = 500) String text) {
    }

    /** {@code added} are the lines produced by this step; {@code over} once the agent has said goodbye. */
    public record TestCall(UUID callId, Purpose purpose, String personName, boolean verified, Outcome outcome,
                           boolean over, List<Line> transcript, List<Line> added) {
    }

    private final VoiceCallRepository calls;
    private final VoiceCallService callService;
    private final VoiceScriptService scripts;
    private final SimulatedBrain brain;
    private final StudentRepository students;
    private final LeadRepository leads;
    private final TransactionTemplate tx;

    public TestCallService(VoiceCallRepository calls, VoiceCallService callService, VoiceScriptService scripts,
                           SimulatedBrain brain, StudentRepository students, LeadRepository leads,
                           TransactionTemplate tx) {
        this.calls = calls;
        this.callService = callService;
        this.scripts = scripts;
        this.brain = brain;
        this.students = students;
        this.leads = leads;
        this.tx = tx;
    }

    public TestCall start(StartRequest req) {
        admin();
        if (req.studentId() != null && req.leadId() != null) {
            throw ApiException.badRequest("Choose a student or a lead, not both");
        }
        record Started(UUID id, String opening) {
        }
        Started started = tx.execute(s -> {
            Student student = req.studentId() == null ? null : students.findById(req.studentId())
                    .orElseThrow(() -> ApiException.badRequest("Student not found"));
            Lead lead = req.leadId() == null ? null : leads.findById(req.leadId())
                    .orElseThrow(() -> ApiException.badRequest("Lead not found"));
            Language language = req.language() != null ? req.language()
                    : student != null ? student.getLanguagePreference()
                    : lead != null ? lead.getLanguagePreference() : Language.ENGLISH;
            String phone = student != null ? student.getPhone() : lead != null ? lead.getPhone() : "0000000000";
            VoiceCall call = new VoiceCall(req.purpose().outbound() ? Direction.OUTBOUND : Direction.INBOUND,
                    req.purpose(), req.studentId(), req.leadId(), phone, language, "TEST");
            call.setTestCall(true);
            call.setStatus(CallStatus.CONNECTED);
            call.setStartedAt(Instant.now());
            Map<String, String> vars = callService.variables(req.purpose(), language, student, lead, 30);
            VoiceScript script = scripts.newest(req.purpose(), language).orElse(null);
            String opening = script == null ? "Hello, I'm an AI assistant. How can I help you?"
                    : VoiceScriptService.render(script.getOpeningLine(), vars);
            if (script != null) {
                call.setScriptId(script.getId());
            }
            calls.save(call);
            return new Started(call.getId(), opening);
        });
        List<Line> added = brain.open(started.id(), started.opening());
        return append(started.id(), added);
    }

    public TestCall say(UUID callId, SayRequest req) {
        admin();
        record Info(Purpose purpose, boolean studentKnown) {
        }
        Info info = tx.execute(s -> {
            VoiceCall call = testCall(callId);
            if (!call.getStatus().live()) {
                throw ApiException.conflict("This test call has ended. Start a new one.");
            }
            return new Info(call.getPurpose(), call.getStudentId() != null);
        });
        List<Line> added = new ArrayList<>();
        added.add(Line.caller(req.text().trim()));
        added.addAll(brain.respond(callId, info.purpose(), info.studentKnown(), req.text()));
        TestCall result = append(callId, added);
        return brain.over(callId) ? end(callId, result.added()) : result;
    }

    public TestCall end(UUID callId) {
        admin();
        return end(callId, List.of());
    }

    public TestCall get(UUID callId) {
        admin();
        return tx.execute(s -> view(testCall(callId), List.of()));
    }

    private TestCall end(UUID callId, List<Line> added) {
        brain.forget(callId);
        return tx.execute(s -> {
            VoiceCall call = testCall(callId);
            if (call.getStatus().live()) {
                callService.close(call, CallStatus.COMPLETED, "TEST_ENDED");
            }
            return view(call, added);
        });
    }

    private TestCall append(UUID callId, List<Line> added) {
        return tx.execute(s -> {
            VoiceCall call = testCall(callId);
            List<Line> all = new ArrayList<>(callService.lines(call));
            all.addAll(added);
            call.setTranscript(callService.toJson(all));
            return view(call, added);
        });
    }

    private TestCall view(VoiceCall call, List<Line> added) {
        String name = call.getStudentId() != null
                ? students.findById(call.getStudentId()).map(Student::getFullName).orElse(null)
                : call.getLeadId() != null ? leads.findById(call.getLeadId()).map(Lead::getFullName).orElse(null)
                : null;
        return new TestCall(call.getId(), call.getPurpose(), name, call.getVerificationLevel() >= 1,
                call.getOutcome(), !call.getStatus().live(), callService.lines(call), added);
    }

    private VoiceCall testCall(UUID callId) {
        return calls.findById(callId).filter(VoiceCall::isTestCall).orElseThrow(() -> ApiException.notFound("Test call"));
    }

    private static void admin() {
        if (!CurrentUser.get().isAdmin()) {
            throw ApiException.forbidden("Only an admin can use the test console");
        }
    }
}
