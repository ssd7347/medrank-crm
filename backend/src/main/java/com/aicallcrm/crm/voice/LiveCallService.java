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

    /**
     * What the agent says next; {@code over} once it has wrapped the call up (the engine then hangs up). {@code hint},
     * on opening only: the registered name of the person called, which the engine gives Whisper so it spells that
     * name as registered (ear check, 11 Nov 2026: every name right with small.en). Never said to the caller.
     */
    public record Reply(String say, boolean over, String hint, boolean record) {
        Reply(String say, boolean over) {
            this(say, over, null, false);
        }
    }

    /** Longest name sent as a hint: a name, not a paragraph (Whisper keeps at most half its context for hints). */
    static final int MAX_HINT = 100;

    private record Opening(String line, String name, boolean recorded) {
    }

    private final VoiceCallRepository calls;
    private final VoiceScriptRepository scriptRepo;
    private final VoiceCallService callService;
    private final Brains brains;
    private final ToolExecutor executor;
    private final VoicePlatforms platforms;
    private final ContactRepository contacts;
    private final HandoffService handoffs;
    private final TransactionTemplate tx;

    public LiveCallService(VoiceCallRepository calls, VoiceScriptRepository scriptRepo, VoiceCallService callService,
                           Brains brains, ToolExecutor executor, VoicePlatforms platforms, ContactRepository contacts,
                           HandoffService handoffs, TransactionTemplate tx) {
        this.calls = calls;
        this.scriptRepo = scriptRepo;
        this.callService = callService;
        this.brains = brains;
        this.executor = executor;
        this.platforms = platforms;
        this.contacts = contacts;
        this.handoffs = handoffs;
        this.tx = tx;
    }

    /** The call connected: the brain reads the context and says the script's opening line. */
    public Reply open(UUID callId) {
        Opening opened = tx.execute(s -> {
            VoiceCall call = live(callId);
            Contact contact = call.getContactId() == null ? null : contacts.findById(call.getContactId()).orElse(null);
            VoiceScript script = call.getScriptId() == null ? null : scriptRepo.findById(call.getScriptId()).orElse(null);
            return new Opening(script == null ? "" : VoiceScriptService.render(script.getOpeningLine(),
                    callService.variables(contact)), hint(contact), call.isRecordingAllowed() && !call.isTestCall());
        });
        String opening = opened.line();
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
        // The engine records only a call that is to be recorded and whose opening, as said, told them so.
        String said = said(added);
        return new Reply(said, brains.of(callId).over(callId), opened.name(),
                opened.recorded() && RecordingService.announces(said));
    }

    /** The contact's registered name for the engine's ear, or null for an unknown caller. */
    static String hint(Contact contact) {
        String name = contact == null || contact.getFullName() == null ? "" : contact.getFullName().strip();
        if (name.isEmpty()) {
            return null;
        }
        return name.length() > MAX_HINT ? name.substring(0, MAX_HINT).strip() : name;
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
        // The caller may have hung up while the brain was answering, so the call was closed before these words were
        // saved: if they asked to stop, record it now ({@link VoiceCallService#optOutIfAsked}, which close() also runs).
        tx.executeWithoutResult(s -> calls.findById(callId).filter(c -> !c.getStatus().live())
                .ifPresent(callService::optOutIfAsked));
        return new Reply(said(added), brain.over(callId));
    }

    /**
     * Can the team call this person back if the call breaks ({@link HandoffService#canCallBack})? Sent to the engine
     * with every reply, so its "having trouble" goodbye promises a call-back only when one will be made.
     */
    public boolean canCallBack(UUID callId) {
        return Boolean.TRUE.equals(tx.execute(s -> calls.findById(callId).map(handoffs::canCallBack).orElse(false)));
    }

    /** {@code transferred}: the call went to the desk instead of being hung up. */
    public record Ended(boolean hungUp, boolean transferred) {
    }

    /**
     * The agent finished and its goodbye has been heard: hang up through the platform, or, if the caller was just told
     * they are being connected, transfer them to the desk now ({@link HandoffService#completeTransfer}). The call's end
     * is recorded when the platform reports the hang-up ({@link VoiceCallService#ended}).
     */
    public Ended end(UUID callId) {
        boolean transferred = handoffs.completeTransfer(callId);
        brains.finished(callId);
        if (transferred) {
            return new Ended(false, true);
        }
        String providerCallId = tx.execute(s -> calls.findById(callId).map(VoiceCall::getProviderCallId).orElse(null));
        return new Ended(providerCallId != null && platforms.current().hangup(providerCallId), false);
    }

    /**
     * The engine hit a fault it could not get past (hearing, speaking, or a request to this CRM failed), told the caller
     * "A member of our team will call you back" and is hanging up: book that call-back ({@link HandoffService#cutOff})
     * and note it in the transcript. True if a task was booked. The call may already have ended by now. The engine
     * sends this again from its outbox when the CRM did not answer, so it may arrive twice: the second time it changes
     * nothing.
     */
    public boolean trouble(UUID callId, String what) {
        String detail = what == null ? "" : what.strip();
        if (detail.length() > 200) {
            detail = detail.substring(0, 200);
        }
        String note = detail.isEmpty() ? null : detail;
        String summary = "The AI call was cut off by a fault on our side" + (note == null ? "" : " (" + note + ")")
                + ". The caller was told a member of the team would call back.";
        return Boolean.TRUE.equals(tx.execute(s -> {
            VoiceCall call = callId == null ? null : calls.findById(callId).orElse(null);
            if (call == null || call.isTestCall() || erased(call)) {
                throw ApiException.notFound("Call");
            }
            List<Line> all = new ArrayList<>(callService.lines(call));
            if (all.stream().anyMatch(l -> "call_cut_off".equals(l.tool()))) {
                return false;       // already reported (a repeat from the engine's outbox)
            }
            boolean booked = handoffs.cutOff(call, summary) != null;
            all.add(Line.tool("call_cut_off", booked ? "CALLBACK_BOOKED" : "NO_CALLBACK_NEEDED", note));
            call.setTranscript(callService.toJson(all));
            return booked;
        }));
    }

    /**
     * The caller's last words: what they said that the engine had not passed on when the stream closed (they hung up
     * mid-sentence, or before the 0.7 s of quiet that ends a turn), heard afterwards. Nothing is said back. The words
     * go into the transcript, and if they asked to stop, the opt-out is recorded at once ({@link
     * VoiceCallService#optOutIfAsked}), whether or not the end of the call has been recorded yet: "Stop calling me"
     * followed by a hang-up must never be rung again. True if this opted them out. Sent again from the engine's outbox
     * when the CRM did not answer, so the same words may arrive twice: words already in the transcript as the caller's
     * are not written again.
     */
    public boolean lastWords(UUID callId, String said) {
        String text = said == null ? "" : said.strip();
        String words = text.length() > 500 ? text.substring(0, 500) : text;
        return Boolean.TRUE.equals(tx.execute(s -> {
            VoiceCall call = callId == null ? null : calls.findById(callId).orElse(null);
            if (call == null || call.isTestCall() || erased(call)) {
                throw ApiException.notFound("Call");
            }
            if (words.isEmpty()) {
                return false;
            }
            List<Line> all = new ArrayList<>(callService.lines(call));
            // A repeat is spotted by its words: an opt-out note may have been written after them the first time.
            if (all.stream().noneMatch(l -> "caller".equals(l.role()) && words.equals(l.text()))) {
                all.add(Line.caller(words));
                call.setTranscript(callService.toJson(all));
            }
            boolean optedOut = callService.optOutIfAsked(call);
            if (call.getEndedAt() != null) {
                callService.callbackWithdrawnIfSaid(call);   // the call is over, so these really are the last words
            }
            return optedOut;
        }));
    }

    /** The platform reported the hang-up (the caller may have hung up first): the brain lets go of the call. */
    public void hungUp(UUID callId, String providerCallId) {
        UUID id = callId != null ? callId : providerCallId == null ? null
                : tx.execute(s -> calls.findByProviderCallId(providerCallId).map(VoiceCall::getId).orElse(null));
        if (id != null) {
            handoffs.missedTransfer(id);
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

    /**
     * The person on this call was erased (ErasureService: the number is blanked; a withheld number is "unknown",
     * never blank). Nothing about them may be written to it again, such as last words the engine re-sends from its
     * outbox, so it is answered as "no such call" and the engine drops the request.
     */
    private static boolean erased(VoiceCall call) {
        return call.getPhone() == null || call.getPhone().isBlank();
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
