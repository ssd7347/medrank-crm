package com.aicallcrm.crm.voice;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.alert.AlertService;
import com.aicallcrm.crm.alert.Priority;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactMarkedDoNotCall;
import com.aicallcrm.crm.contact.ContactRepository;
import com.aicallcrm.crm.contact.ContactStatus;
import com.aicallcrm.crm.user.AppUser;
import com.aicallcrm.crm.voice.Voice.HandoffReason;
import com.aicallcrm.crm.voice.Voice.Outcome;

/**
 * Passing a caller to a person. If the desk is open and the platform can transfer, the call is transferred;
 * otherwise a call-back task is created with a due time, the contact's owner (or the managers) is told at
 * once, and the caller hears when to expect the call. Complaints and distress are urgent.
 *
 * <p>On our own speech engine the transfer waits until the caller has heard "I am connecting you" (the engine's
 * {@code end} step, {@link #completeTransfer}). If it then fails, or the caller hangs up first, an urgent call-back is
 * booked instead ({@link #missedTransfer}), so nobody who was promised a person is left without one.
 */
@Service
public class HandoffService {

    public static final String TRANSFER_NOW = "TRANSFER_NOW";
    public static final String CALLBACK_TASK = "CALLBACK_TASK";
    /** A person was already asked for earlier in this call; no second task is made. */
    public static final String ALREADY_ARRANGED = "ALREADY_ARRANGED";

    /**
     * {@code callbackId} is null for a live transfer and for test calls, which save nothing. {@code slot} is the
     * promised time as the caller hears it ("Tuesday 17 November, 4:00 PM"); null when there is none.
     */
    public record Handoff(String mode, String message, Long callbackId, Instant dueAt, String slot) {
        Handoff(String mode, String message, Long callbackId, Instant dueAt) {
            this(mode, message, callbackId, dueAt, dueAt == null ? null : ContactFacts.spokenTime(dueAt));
        }
    }

    private final CallbackRequestRepository callbacks;
    private final ContactRepository contacts;
    private final AlertService alerts;
    private final VoicePlatforms platforms;
    private final VoiceProperties props;
    private final VoiceCallRepository calls;
    private final Clock clock;

    public HandoffService(CallbackRequestRepository callbacks, ContactRepository contacts, AlertService alerts,
                          VoicePlatforms platforms, VoiceProperties props, VoiceCallRepository calls, Clock clock) {
        this.callbacks = callbacks;
        this.contacts = contacts;
        this.alerts = alerts;
        this.platforms = platforms;
        this.props = props;
        this.calls = calls;
        this.clock = clock;
    }

    @Transactional
    public Handoff handoff(VoiceCall call, HandoffReason reason, String summary) {
        Instant now = Instant.now(clock);
        if (reason == HandoffReason.VERIFICATION_FAILED) {
            call.setOutcome(Outcome.VERIFICATION_FAILED);
        } else if (call.getOutcome() != Outcome.OPTED_OUT && call.getOutcome() != Outcome.VERIFICATION_FAILED) {
            call.setOutcome(Outcome.HANDED_OFF);
        }
        HandoffReason earlier = call.getHandoffReason();
        if (earlier != null && urgency(reason) <= urgency(earlier)) {
            // A second hand-over in the same call (the model asking twice): the team already has the first one.
            return new Handoff(ALREADY_ARRANGED, "Our team already has your request from this call, so they will be in "
                    + "touch as arranged.", null, null);
        }
        call.setHandoffReason(reason);
        if (call.isTestCall()) {
            Instant due = dueAt(reason, now);
            return new Handoff(CALLBACK_TASK, callbackMessage(due), null, due);
        }
        Contact contact = contact(call);
        boolean canTransfer = platforms.live() && call.getProviderCallId() != null
                && !props.deskTransferNumber().isBlank() && deskOpen(now);
        VoicePlatformClient platform = platforms.current();
        boolean afterGoodbye = canTransfer && platform.transfersAfterGoodbye();
        if (afterGoodbye) {
            call.setTransferPending(true);      // transferred once the caller has heard the line below (completeTransfer)
        }
        boolean transferred = afterGoodbye
                || canTransfer && platform.transfer(call.getProviderCallId(), props.deskTransferNumber(), summary);
        if (transferred) {
            alerts.notifyOwnerOrManagers(owner(contact), call.getContactId(), "VOICE_CALLBACK",
                    reason.high() ? Priority.URGENT : Priority.NORMAL,
                    "AI call transferred to the desk: " + label(call, contact), summary, "/voice/calls/" + call.getId(),
                    "VXFER:" + call.getId());
            return new Handoff(TRANSFER_NOW, "I am connecting you to a member of our team now. Please stay on the line.",
                    null, null);
        }
        CallbackRequest cb = task(call, contact, reason.name(), null, summary,
                reason.high() ? Priority.URGENT : Priority.NORMAL, dueAt(reason, now));
        return new Handoff(CALLBACK_TASK, callbackMessage(cb.getDueAt()), cb.getId(), cb.getDueAt());
    }

    /**
     * Our engine has played "I am connecting you" and asks to end the call: transfer it now. True if the platform took
     * it. False if no transfer was waiting, or if it failed: then an urgent call-back is booked and the caller is hung
     * up on as usual.
     */
    @Transactional
    public boolean completeTransfer(UUID callId) {
        if (calls.claimTransfer(callId) == 0) {
            return false;
        }
        VoiceCall call = calls.findById(callId).orElseThrow();
        if (call.getProviderCallId() != null
                && platforms.current().transfer(call.getProviderCallId(), props.deskTransferNumber(), null)) {
            return true;
        }
        book(call, "The transfer to the desk did not go through");
        return false;
    }

    /** The caller hung up while a transfer was waiting: they still get the person they asked for. */
    @Transactional
    public void missedTransfer(UUID callId) {
        if (calls.claimTransfer(callId) == 1) {
            book(calls.findById(callId).orElseThrow(), "The caller hung up before the transfer to the desk");
        }
    }

    private void book(VoiceCall call, String what) {
        HandoffReason reason = call.getHandoffReason() == null ? HandoffReason.CALLER_REQUEST : call.getHandoffReason();
        task(call, contact(call), reason.name(), null, what + ". They asked for a person and were told they were being "
                + "connected: call them back now. The call's transcript has the details.", Priority.URGENT,
                Instant.now(clock).plus(Duration.ofMinutes(15)));
    }

    /**
     * The caller simply asked to be called back; not an escalation. They are promised the time they asked for when it
     * can be kept ({@link CallbackTime}), otherwise the usual two hours of desk time.
     */
    @Transactional
    public Handoff callback(VoiceCall call, String preferredTimeText, String reason) {
        Instant now = Instant.now(clock);
        CallbackTime.Promise promise = promise(preferredTimeText, now);
        if (call.getOutcome() == null) {
            call.setOutcome(Outcome.CALLBACK_REQUESTED);
        }
        if (call.isTestCall()) {
            return handoff(promise, null);
        }
        // Asked twice in one call (the model booking again, or the CRM booking for a logged outcome): one task.
        CallbackRequest open = callbacks.findByVoiceCallId(call.getId()).stream()
                .filter(c -> "CALLBACK_REQUESTED".equals(c.getReason()) && c.getStatus() != Voice.CallbackStatus.DONE)
                .findFirst().orElse(null);
        if (open != null) {
            CallbackTime.Promise booked = promise(open.getPreferredTimeText(), now);
            if (!booked.due().equals(open.getDueAt())) {
                booked = CallbackTime.by(open.getDueAt());
            }
            if (promise.asked() && !promise.allows(open.getDueAt())) {
                return moved(call, open, preferredTimeText, booked, promise);
            }
            // The same request again, said vaguer ("this afternoon" after "4 pm"), or one that cannot be kept: the
            // caller hears the time already booked (after the desk hours, if they asked outside them).
            return handoff(promise.note() == null ? booked : booked.withNote(promise.note()), open.getId());
        }
        CallbackRequest cb = task(call, contact(call), "CALLBACK_REQUESTED", preferredTimeText, reason, Priority.NORMAL,
                promise.due(), promise.when());
        return handoff(promise, cb.getId());
    }

    /**
     * The caller changed the time ("actually, make it 5"): the task moves to it, and whoever has the task (or the
     * contact's owner, or the managers) is told, since they may have planned the call for the first time.
     */
    private Handoff moved(VoiceCall call, CallbackRequest open, String preferredTimeText, CallbackTime.Promise before,
                          CallbackTime.Promise now) {
        open.reschedule(now.due(), cut(preferredTimeText, 200));
        Contact contact = contact(call);
        AppUser who = open.getAssignedTo() != null && open.getAssignedTo().isActive() ? open.getAssignedTo() : owner(contact);
        alerts.notifyOwnerOrManagers(who, call.getContactId(), "VOICE_CALLBACK", open.getPriority(),
                "Call-back time changed, call back " + now.when() + ": " + label(call, contact),
                "Asked for: " + preferredTimeText + ". It was " + before.when() + ".", "/voice/callbacks",
                "VCB:" + open.getId() + ":" + now.due().getEpochSecond());
        return handoff(now, open.getId());
    }

    CallbackTime.Promise promise(String preferredTimeText, Instant now) {
        return CallbackTime.promise(preferredTimeText, now, props.deskOpen(), props.deskClose(), dueAt(null, now));
    }

    private static Handoff handoff(CallbackTime.Promise p, Long callbackId) {
        return new Handoff(CALLBACK_TASK, p.message(), callbackId, p.due(), p.slot());
    }

    /**
     * The call broke on our side (the speech engine or the CRM failed) and the caller was told a member of the team
     * will call back: a call-back task, never a transfer, because the engine is hanging up. Nothing (null) for a test
     * call, a caller who opted out or is marked "Do not call", or a call that already has an open call-back.
     */
    @Transactional
    public CallbackRequest cutOff(VoiceCall call, String summary) {
        // The caller now hears "a member of our team will call you back": no transfer after that.
        call.setTransferPending(false);
        Contact contact = contact(call);
        if (call.isTestCall() || call.getOutcome() == Outcome.OPTED_OUT
                || contact != null && ContactStatus.DO_NOT_CALL.equals(contact.getStatus())
                || callbacks.findByVoiceCallId(call.getId()).stream()
                        .anyMatch(c -> c.getStatus() != Voice.CallbackStatus.DONE)) {
            return null;
        }
        if (call.getOutcome() == null) {
            call.setOutcome(Outcome.HANDED_OFF);
        }
        if (call.getHandoffReason() == null) {
            call.setHandoffReason(HandoffReason.TOOL_FAILURE);
        }
        return task(call, contact, HandoffReason.TOOL_FAILURE.name(), null, summary, Priority.NORMAL,
                dueAt(HandoffReason.TOOL_FAILURE, Instant.now(clock)));
    }

    static final String CANCELLED_NOTE = "Cancelled by the CRM: on an AI call the caller asked not to be called at all.";
    static final String AI_ONLY_NOTE = "CHECK BEFORE CALLING: on an AI call they then asked to stop AI calls. They did "
            + "not say no to a call from staff, but read the transcript first.";

    /**
     * The caller opted out on this call: their open call-backs, from this call or earlier ones, must not be rung as if
     * nothing happened. Asked for no calls at all ("Do not call"): each is cancelled (closed with a note), since staff
     * must not ring them either. Asked to stop AI calls only: each stays, since they may still want the person they
     * asked for, but it says so at the top and whoever has it (or the contact's owner, or the managers) is told to read
     * the transcript first. Returns how many call-backs this touched; none for a test call.
     */
    @Transactional
    public int optedOut(VoiceCall call, boolean allCalls) {
        if (call.isTestCall()) {
            return 0;
        }
        List<CallbackRequest> open = call.getContactId() != null
                ? callbacks.findByContactIdAndStatusIn(call.getContactId(),
                        List.of(Voice.CallbackStatus.OPEN, Voice.CallbackStatus.ASSIGNED))
                : callbacks.findByVoiceCallId(call.getId()).stream()
                        .filter(c -> c.getStatus() != Voice.CallbackStatus.DONE).toList();
        if (open.isEmpty()) {
            return 0;
        }
        if (allCalls) {
            open.forEach(cb -> cb.complete(null, CANCELLED_NOTE));
            return open.size();
        }
        Contact contact = contact(call);
        flag(call, contact, open, AI_ONLY_NOTE, "Check before calling back: " + label(call, contact),
                label(call, contact) + " asked on an AI call to stop AI calls and has " + open.size()
                        + (open.size() == 1 ? " open call-back" : " open call-backs") + ". They did not say no to a call "
                        + "from staff, but read the transcript first.", "VOPT:" + call.getId());
        return open.size();
    }

    static final String WITHDRAWN_NOTE = "CHECK BEFORE CALLING: later in the AI call they said they do not need a "
            + "call-back. Read the transcript; if that is right, mark this done.";

    /**
     * The caller took the call-back back ("no need to call me back", {@link CallerSignals#callbackWithdrawn}) after it
     * was booked, or the model booked one anyway. Only the call-backs this call booked: a "no" to the agent's offer of
     * a call-back says nothing about one booked on another day (a dispute, say). They are not closed, since the words
     * come from a phone line and a closed call-back cannot be reopened: each starts with {@link #WITHDRAWN_NOTE}, and
     * whoever has it (or the contact's owner, or the managers) is told, with the caller's words. Returns how many
     * call-backs this touched; none for a test call.
     */
    @Transactional
    public int withdrawn(VoiceCall call, String sentence) {
        if (call.isTestCall()) {
            return 0;
        }
        List<CallbackRequest> open = callbacks.findByVoiceCallId(call.getId()).stream()
                .filter(c -> c.getStatus() != Voice.CallbackStatus.DONE).toList();
        if (open.isEmpty()) {
            return 0;
        }
        Contact contact = contact(call);
        String said = sentence.length() <= 200 ? sentence : sentence.substring(0, 200) + "...";
        flag(call, contact, open, WITHDRAWN_NOTE, "Call-back may not be needed: " + label(call, contact),
                label(call, contact) + " said on an AI call: \"" + said + "\" Read the transcript before calling; if "
                        + "they do not need the call-back, mark it done.", "VWD:" + call.getId());
        return open.size();
    }

    /** Puts a warning on each call-back and tells whoever has one, or else the contact's owner or the managers. */
    private void flag(VoiceCall call, Contact contact, List<CallbackRequest> open, String note, String title,
                      String body, String key) {
        Set<AppUser> told = new LinkedHashSet<>();
        for (CallbackRequest cb : open) {
            cb.warn(note);
            if (cb.getAssignedTo() != null && cb.getAssignedTo().isActive()) {
                told.add(cb.getAssignedTo());
            }
        }
        if (told.isEmpty()) {
            alerts.notifyOwnerOrManagers(owner(contact), call.getContactId(), "VOICE_CALLBACK", Priority.NORMAL,
                    title, body, "/voice/callbacks", key);
        }
        for (AppUser u : told) {
            alerts.notifyUser(u.getId(), call.getContactId(), "VOICE_CALLBACK", Priority.NORMAL, title, body,
                    "/voice/callbacks", key);
        }
    }

    static final String DO_NOT_CALL_NOTE = "Cancelled by the CRM: the contact was marked \"Do not call\".";

    /**
     * A contact became "Do not call" (staff on the contact page, a bulk change, a merge, an AI call): nobody may ring
     * them, so their open call-backs are closed with a note. Whoever had taken one is told, unless they made the change.
     * Changing the status back does not reopen them.
     */
    @EventListener
    @Transactional
    public void markedDoNotCall(ContactMarkedDoNotCall e) {
        List<CallbackRequest> open = callbacks.findByContactIdAndStatusIn(e.contactId(),
                List.of(Voice.CallbackStatus.OPEN, Voice.CallbackStatus.ASSIGNED));
        if (open.isEmpty()) {
            return;
        }
        String name = contacts.findById(e.contactId()).map(Contact::getFullName).orElse("A contact");
        for (CallbackRequest cb : open) {
            AppUser had = cb.getAssignedTo();
            cb.complete(null, DO_NOT_CALL_NOTE);
            if (had != null && had.isActive() && !had.getId().equals(e.byUserId())) {
                alerts.notifyUser(had.getId(), e.contactId(), "VOICE_CALLBACK", Priority.NORMAL,
                        "Call-back cancelled: " + name, name + " was marked \"Do not call\", so the call-back you "
                                + "took is closed. Please do not call them.", "/voice/callbacks", "VDNC:" + cb.getId());
            }
        }
    }

    /** Tells the contact's owner (or the managers) something they should know about this call. */
    @Transactional
    public void inform(VoiceCall call, Priority priority, String title, String body) {
        Contact contact = contact(call);
        alerts.notifyOwnerOrManagers(owner(contact), call.getContactId(), "VOICE_CALLBACK", priority,
                title + ": " + label(call, contact), body, "/voice/calls/" + call.getId(), "VINF:" + call.getId());
    }

    private CallbackRequest task(VoiceCall call, Contact contact, String reason, String preferredTimeText,
                                 String summary, Priority priority, Instant dueAt) {
        return task(call, contact, reason, preferredTimeText, summary, priority, dueAt,
                "by " + ContactFacts.spokenTime(dueAt));
    }

    /** {@code when}: as the caller was told, after "call you back" ("by Monday ...", "on Tuesday ... at 4:00 PM"). */
    private CallbackRequest task(VoiceCall call, Contact contact, String reason, String preferredTimeText,
                                 String summary, Priority priority, Instant dueAt, String when) {
        CallbackRequest cb = callbacks.save(new CallbackRequest(call.getContactId(), call.getId(), call.getPhone(),
                reason, cut(preferredTimeText, 200), cut(summary, 2000), priority, owner(contact), dueAt));
        alerts.notifyOwnerOrManagers(owner(contact), call.getContactId(), "VOICE_CALLBACK", priority,
                "Call back " + when + ": " + label(call, contact),
                (preferredTimeText == null || preferredTimeText.isBlank() ? "" : "Asked for: " + preferredTimeText + ". ")
                        + (summary == null ? "" : summary), "/voice/callbacks", "VCB:" + cb.getId());
        return cb;
    }

    private Contact contact(VoiceCall call) {
        return call.getContactId() == null ? null : contacts.findById(call.getContactId()).orElse(null);
    }

    /** The staff member who already looks after this contact, if they are still active. */
    private static AppUser owner(Contact contact) {
        AppUser owner = contact == null ? null : contact.getAssignedTo();
        return owner != null && owner.isActive() ? owner : null;
    }

    private static String label(VoiceCall call, Contact contact) {
        return contact != null ? contact.getFullName() : "caller " + mask(call.getPhone());
    }

    /** Distress, then complaints and disputes, then everything else: a later hand-over only counts if it is higher. */
    static int urgency(HandoffReason reason) {
        return reason == HandoffReason.DISTRESS ? 2 : reason.high() ? 1 : 0;
    }

    // ------------------------------------------------------------------ due times

    /**
     * Distress: within 15 minutes whatever the hour. Complaints and disputes: 30 minutes of desk time.
     * Everything else: two hours of desk time.
     */
    Instant dueAt(HandoffReason reason, Instant now) {
        if (reason == HandoffReason.DISTRESS) {
            return now.plus(Duration.ofMinutes(15));
        }
        Duration allowed = reason != null && reason.high() ? Duration.ofMinutes(30) : Duration.ofHours(2);
        return plusDeskTime(now, allowed, props.deskOpen(), props.deskClose());
    }

    /**
     * {@code allowed} counted in desk time only: two hours from 5:30 PM, with the desk closing at 6:30, end an hour
     * after the next opening, not at 7:30 PM when nobody is there to call.
     */
    static Instant plusDeskTime(Instant now, Duration allowed, LocalTime open, LocalTime close) {
        Instant t = deskTime(now, open, close);
        if (!open.isBefore(close)) {
            return t.plus(allowed);             // desk hours set wrongly: as before, rather than loop
        }
        Duration left = allowed;
        for (int day = 0; day < 14; day++) {
            Instant closing = t.atZone(ContactFacts.IST).with(close).toInstant();
            Duration today = Duration.between(t, closing);
            if (left.compareTo(today) <= 0) {
                return t.plus(left);
            }
            left = left.minus(today);
            t = deskTime(closing, open, close);
        }
        return t.plus(left);
    }

    /** Is there a desk number, and is the desk open now? */
    boolean deskOpenNow() {
        return !props.deskTransferNumber().isBlank() && deskOpen(Instant.now(clock));
    }

    /** Is a call-back booked from this call still to be made? */
    boolean callBackOpen(UUID callId) {
        return callbacks.findByVoiceCallId(callId).stream().anyMatch(c -> c.getStatus() != Voice.CallbackStatus.DONE);
    }

    boolean deskOpen(Instant now) {
        return deskTime(now, props.deskOpen(), props.deskClose()).equals(now);
    }

    /** {@code now} if the desk is open (Monday to Saturday, desk hours, Indian time); otherwise its next opening. */
    static Instant deskTime(Instant now, LocalTime open, LocalTime close) {
        ZonedDateTime t = now.atZone(ContactFacts.IST);
        if (t.getDayOfWeek() != DayOfWeek.SUNDAY && !t.toLocalTime().isBefore(open) && t.toLocalTime().isBefore(close)) {
            return now;
        }
        ZonedDateTime next = t.toLocalTime().isBefore(open) ? t.with(open) : t.plusDays(1).with(open);
        if (next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next.toInstant();
    }

    private static String callbackMessage(Instant due) {
        return CallbackTime.by(due).message();
    }

    static String mask(String phone) {
        return phone == null || phone.length() <= 4 ? "****" : "******" + phone.substring(phone.length() - 4);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
