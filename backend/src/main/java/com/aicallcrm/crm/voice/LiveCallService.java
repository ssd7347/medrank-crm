package com.aicallcrm.crm.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactRepository;
import com.aicallcrm.crm.voice.VoiceCallService.Line;

/**
 * A real phone call held by our own speech engine (voice-engine/, the {@code /call} stream): the engine turns
 * the caller's speech into text, asks here what to say, and speaks the answer. The conversation runs on the same
 * brains, tools, rules and transcript as the test console; only the call is real.
 */
@Service
public class LiveCallService {

    private static final Logger log = LoggerFactory.getLogger(LiveCallService.class);

    /** What the agent says next; {@code over} once it has wrapped the call up (the engine then hangs up). */
    public record Reply(String say, boolean over) {
    }

    private final VoiceCallRepository calls;
    private final VoiceScriptRepository scriptRepo;
    private final VoiceCallService callService;
    private final Brains brains;
    private final ToolExecutor executor;
    private final VoicePlatforms platforms;
    private final ContactRepository contacts;
    private final TransactionTemplate tx;

    public LiveCallService(VoiceCallRepository calls, VoiceScriptRepository scriptRepo, VoiceCallService callService,
                           Brains brains, ToolExecutor executor, VoicePlatforms platforms, ContactRepository contacts,
                           TransactionTemplate tx) {
        this.calls = calls;
        this.scriptRepo = scriptRepo;
        this.callService = callService;
        this.brains = brains;
        this.executor = executor;
        this.platforms = platforms;
        this.contacts = contacts;
        this.tx = tx;
    }

    /** The call connected: the brain reads the context and says the script's opening line. */
    public Reply open(UUID callId) {
        String opening = tx.execute(s -> {
            VoiceCall call = live(callId);
            Contact contact = call.getContactId() == null ? null : contacts.findById(call.getContactId()).orElse(null);
            VoiceScript script = call.getScriptId() == null ? null : scriptRepo.findById(call.getScriptId()).orElse(null);
            return script == null ? "" : VoiceScriptService.render(script.getOpeningLine(), callService.variables(contact));
        });
        List<Line> added;
        try {
            added = brains.begin(callId, null).open(callId, opening);
        } catch (ApiException e) {
            // The configured brain cannot run (for example Claude without a key): a person calls back instead.
            log.warn("Live call {} could not start its brain: {}", callId, e.getMessage());
            added = new ArrayList<>();
            ToolResult r = executor.execute(callId, "request_human_handoff",
                    Map.of("reason_code", "TOOL_FAILURE", "summary", "The AI agent could not start on this call."));
            added.add(Line.tool("request_human_handoff", r.ok() ? "OK" : r.errorCode(), null));
            added.add(Line.agent((r.ok() ? r.speakHint() : "A member of our team will call you back.") + " Goodbye."));
            append(callId, added);
            return new Reply(said(added), true);
        }
        append(callId, added);
        return new Reply(said(added), brains.of(callId).over(callId));
    }

    /** One turn: the caller said {@code said}. */
    public Reply turn(UUID callId, String said) {
        tx.executeWithoutResult(s -> live(callId));
        String text = said == null ? "" : said.trim();
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }
        List<Line> added = new ArrayList<>();
        added.add(Line.caller(text));
        ConversationBrain brain = brains.of(callId);
        added.addAll(brain.respond(callId, text));
        append(callId, added);
        return new Reply(said(added), brain.over(callId));
    }

    /**
     * The agent finished and its goodbye has been heard: hang up through the platform. The call's end is recorded
     * when the platform reports the hang-up ({@link VoiceCallService#ended}).
     */
    public boolean end(UUID callId) {
        String providerCallId = tx.execute(s -> calls.findById(callId).map(VoiceCall::getProviderCallId).orElse(null));
        brains.finished(callId);
        return providerCallId != null && platforms.current().hangup(providerCallId);
    }

    /** The platform reported the hang-up (the caller may have hung up first): the brain lets go of the call. */
    public void hungUp(UUID callId, String providerCallId) {
        UUID id = callId != null ? callId : providerCallId == null ? null
                : tx.execute(s -> calls.findByProviderCallId(providerCallId).map(VoiceCall::getId).orElse(null));
        if (id != null) {
            brains.finished(id);
        }
    }

    private void append(UUID callId, List<Line> added) {
        tx.executeWithoutResult(s -> {
            VoiceCall call = calls.findById(callId).orElseThrow();
            List<Line> all = new ArrayList<>(callService.lines(call));
            all.addAll(added);
            call.setTranscript(callService.toJson(all));
        });
    }

    private VoiceCall live(UUID callId) {
        VoiceCall call = callId == null ? null : calls.findById(callId).orElse(null);
        if (call == null || call.isTestCall()) {
            throw ApiException.notFound("Call");
        }
        if (!call.getStatus().live()) {
            throw ApiException.conflict("This call has ended");
        }
        return call;
    }

    private static String said(List<Line> lines) {
        return lines.stream().filter(l -> "agent".equals(l.role()) && l.text() != null && !l.text().isBlank())
                .map(Line::text).collect(Collectors.joining(" "));
    }
}
