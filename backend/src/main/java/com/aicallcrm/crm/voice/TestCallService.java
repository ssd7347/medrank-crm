package com.aicallcrm.crm.voice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactRepository;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.voice.Voice.CallStatus;
import com.aicallcrm.crm.voice.Voice.Direction;
import com.aicallcrm.crm.voice.Voice.Outcome;
import com.aicallcrm.crm.voice.Voice.ScriptKind;
import com.aicallcrm.crm.voice.VoiceCallService.Line;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The test console: the admin types what a caller would say and watches the agent answer, tool by tool,
 * against a real contact's record. A test call is marked as such and changes nothing for the contact: no
 * call-back task, no opt-out, no status change and no entry in their call history.
 */
@Service
public class TestCallService {

    public record StartRequest(Long contactId, @NotBlank @Size(max = 80) String scriptName) {
    }

    public record SayRequest(@NotBlank @Size(max = 500) String text) {
    }

    /** {@code added} are the lines produced by this step; {@code over} once the agent has said goodbye. */
    public record TestCall(UUID callId, String scriptName, String contactName, boolean verified, Outcome outcome,
                           boolean over, List<Line> transcript, List<Line> added) {
    }

    private final VoiceCallRepository calls;
    private final VoiceScriptRepository scriptRepo;
    private final VoiceCallService callService;
    private final VoiceScriptService scripts;
    private final SimulatedBrain brain;
    private final ContactRepository contacts;
    private final TransactionTemplate tx;

    public TestCallService(VoiceCallRepository calls, VoiceScriptRepository scriptRepo, VoiceCallService callService,
                           VoiceScriptService scripts, SimulatedBrain brain, ContactRepository contacts,
                           TransactionTemplate tx) {
        this.calls = calls;
        this.scriptRepo = scriptRepo;
        this.callService = callService;
        this.scripts = scripts;
        this.brain = brain;
        this.contacts = contacts;
        this.tx = tx;
    }

    public TestCall start(StartRequest req) {
        admin();
        record Started(UUID id, String opening) {
        }
        Started started = tx.execute(s -> {
            Contact contact = req.contactId() == null ? null : contacts.findById(req.contactId())
                    .orElseThrow(() -> ApiException.badRequest("Contact not found"));
            VoiceScript script = scripts.newest(req.scriptName().trim())
                    .orElseThrow(() -> ApiException.badRequest("No script named \"" + req.scriptName() + "\""));
            Direction direction = script.getKind() == ScriptKind.INBOUND ? Direction.INBOUND : Direction.OUTBOUND;
            VoiceCall call = new VoiceCall(direction, script.getName(), contact == null ? null : contact.getId(),
                    contact == null ? "0000000000" : contact.getPhone(), "TEST");
            call.setTestCall(true);
            call.setStatus(CallStatus.CONNECTED);
            call.setStartedAt(Instant.now());
            call.setScriptId(script.getId());
            calls.save(call);
            Map<String, String> vars = callService.variables(contact);
            return new Started(call.getId(), VoiceScriptService.render(script.getOpeningLine(), vars));
        });
        List<Line> added = brain.open(started.id(), started.opening());
        return append(started.id(), added);
    }

    /** All script names, for the console's drop-down. */
    public List<String> scriptNames() {
        admin();
        return tx.execute(s -> scriptRepo.findAllByOrderByNameAscVersionDesc().stream().map(VoiceScript::getName)
                .distinct().toList());
    }

    public TestCall say(UUID callId, SayRequest req) {
        admin();
        tx.executeWithoutResult(s -> {
            VoiceCall call = testCall(callId);
            if (!call.getStatus().live()) {
                throw ApiException.conflict("This test call has ended. Start a new one.");
            }
        });
        List<Line> added = new ArrayList<>();
        added.add(Line.caller(req.text().trim()));
        added.addAll(brain.respond(callId, req.text()));
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
        String name = call.getContactId() == null ? null
                : contacts.findById(call.getContactId()).map(Contact::getFullName).orElse(null);
        return new TestCall(call.getId(), call.getScriptName(), name, call.getVerificationLevel() >= 1,
                call.getOutcome(), !call.getStatus().live(), callService.lines(call), added);
    }

    private VoiceCall testCall(UUID callId) {
        return calls.findById(callId).filter(VoiceCall::isTestCall).orElseThrow(() -> ApiException.notFound("Test call"));
    }

    private static void admin() {
        if (!CurrentUser.get().isAdmin()) {
            throw ApiException.forbidden("Only the admin can use the test console");
        }
    }
}
