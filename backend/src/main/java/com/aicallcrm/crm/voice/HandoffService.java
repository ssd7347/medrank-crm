package com.aicallcrm.crm.voice;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.alert.AlertService;
import com.aicallcrm.crm.alert.Priority;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactRepository;
import com.aicallcrm.crm.user.AppUser;
import com.aicallcrm.crm.voice.Voice.HandoffReason;
import com.aicallcrm.crm.voice.Voice.Outcome;

/**
 * Passing a caller to a person. If the desk is open and the platform can transfer, the call is transferred;
 * otherwise a call-back task is created with a due time, the contact's owner (or the managers) is told at
 * once, and the caller hears when to expect the call. Complaints and distress are urgent.
 */
@Service
public class HandoffService {

    public static final String TRANSFER_NOW = "TRANSFER_NOW";
    public static final String CALLBACK_TASK = "CALLBACK_TASK";

    /** {@code callbackId} is null for a live transfer and for test calls, which save nothing. */
    public record Handoff(String mode, String message, Long callbackId, Instant dueAt) {
    }

    private final CallbackRequestRepository callbacks;
    private final ContactRepository contacts;
    private final AlertService alerts;
    private final VoicePlatforms platforms;
    private final VoiceProperties props;

    public HandoffService(CallbackRequestRepository callbacks, ContactRepository contacts, AlertService alerts,
                          VoicePlatforms platforms, VoiceProperties props) {
        this.callbacks = callbacks;
        this.contacts = contacts;
        this.alerts = alerts;
        this.platforms = platforms;
        this.props = props;
    }

    @Transactional
    public Handoff handoff(VoiceCall call, HandoffReason reason, String summary) {
        Instant now = Instant.now();
        call.setHandoffReason(reason);
        if (reason == HandoffReason.VERIFICATION_FAILED) {
            call.setOutcome(Outcome.VERIFICATION_FAILED);
        } else if (call.getOutcome() != Outcome.OPTED_OUT && call.getOutcome() != Outcome.VERIFICATION_FAILED) {
            call.setOutcome(Outcome.HANDED_OFF);
        }
        if (call.isTestCall()) {
            Instant due = dueAt(reason, now);
            return new Handoff(CALLBACK_TASK, callbackMessage(due), null, due);
        }
        Contact contact = contact(call);
        boolean transferred = platforms.live() && call.getProviderCallId() != null
                && !props.deskTransferNumber().isBlank() && deskOpen(now)
                && platforms.current().transfer(call.getProviderCallId(), props.deskTransferNumber(), summary);
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

    /** The caller simply asked to be called back; not an escalation. */
    @Transactional
    public Handoff callback(VoiceCall call, String preferredTimeText, String reason) {
        Instant due = dueAt(null, Instant.now());
        if (call.getOutcome() == null) {
            call.setOutcome(Outcome.CALLBACK_REQUESTED);
        }
        if (call.isTestCall()) {
            return new Handoff(CALLBACK_TASK, callbackMessage(due), null, due);
        }
        CallbackRequest cb = task(call, contact(call), "CALLBACK_REQUESTED", preferredTimeText, reason, Priority.NORMAL,
                due);
        return new Handoff(CALLBACK_TASK, callbackMessage(cb.getDueAt()), cb.getId(), cb.getDueAt());
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
        CallbackRequest cb = callbacks.save(new CallbackRequest(call.getContactId(), call.getId(), call.getPhone(),
                reason, cut(preferredTimeText, 200), cut(summary, 2000), priority, owner(contact), dueAt));
        alerts.notifyOwnerOrManagers(owner(contact), call.getContactId(), "VOICE_CALLBACK", priority,
                "Call back by " + ContactFacts.spokenTime(dueAt) + ": " + label(call, contact),
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
        return deskTime(now, props.deskOpen(), props.deskClose()).plus(allowed);
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
        return "A member of our team will call you back by " + ContactFacts.spokenTime(due) + ".";
    }

    static String mask(String phone) {
        return phone == null || phone.length() <= 4 ? "****" : "******" + phone.substring(phone.length() - 4);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
