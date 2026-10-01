package com.mbbscrm.crm.voice;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.grievance.GrievanceService;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.voice.Voice.HandoffReason;
import com.mbbscrm.crm.voice.Voice.Outcome;

/**
 * Passing a caller to a person (spec 18.13). If the desk is open and the platform can transfer, the call
 * is transferred; otherwise a callback task is created with a due time, the counsellor is told at once,
 * and the caller hears when to expect the call. Refund and dispute calls also open a grievance.
 */
@Service
public class HandoffService {

    public static final String TRANSFER_NOW = "TRANSFER_NOW";
    public static final String CALLBACK_TASK = "CALLBACK_TASK";

    /** {@code callbackId} is null for a live transfer and for test calls, which save nothing. */
    public record Handoff(String mode, String message, Long callbackId, Instant dueAt) {
    }

    private final CallbackRequestRepository callbacks;
    private final StudentRepository students;
    private final LeadRepository leads;
    private final AppUserRepository users;
    private final AlertService alerts;
    private final GrievanceService grievances;
    private final VoicePlatforms platforms;
    private final VoiceProperties props;

    public HandoffService(CallbackRequestRepository callbacks, StudentRepository students, LeadRepository leads,
                          AppUserRepository users, AlertService alerts, GrievanceService grievances,
                          VoicePlatforms platforms, VoiceProperties props) {
        this.callbacks = callbacks;
        this.students = students;
        this.leads = leads;
        this.users = users;
        this.alerts = alerts;
        this.grievances = grievances;
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
        Student student = call.getStudentId() == null ? null : students.findById(call.getStudentId()).orElse(null);
        if (reason == HandoffReason.REFUND_OR_DISPUTE) {
            String name = personName(call, student);
            String ref = grievances.openFromVoiceCall(student, name == null ? "Caller " + mask(call.getPhone()) : name,
                    call.getPhone(), summary);
            summary = summary + " (Grievance " + ref + " opened.)";
        }
        boolean transferred = platforms.live() && call.getProviderCallId() != null
                && !props.deskTransferNumber().isBlank() && deskOpen(now)
                && platforms.current().transfer(call.getProviderCallId(), props.deskTransferNumber(), summary);
        if (transferred) {
            notifyStaff(call, student, reason.high() ? Priority.URGENT : Priority.NORMAL,
                    "AI call transferred to the desk: " + label(call, student), summary, "/voice/calls/" + call.getId(),
                    "VXFER:" + call.getId());
            return new Handoff(TRANSFER_NOW, "I am connecting you to a counsellor now. Please stay on the line.",
                    null, null);
        }
        CallbackRequest cb = task(call, student, reason.name(), null, summary,
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
        String confirmed = "A counsellor will call you back by " + VoiceFacts.when(due) + ".";
        if (call.isTestCall()) {
            return new Handoff(CALLBACK_TASK, confirmed, null, due);
        }
        Student student = call.getStudentId() == null ? null : students.findById(call.getStudentId()).orElse(null);
        CallbackRequest cb = task(call, student, "CALLBACK_REQUESTED", preferredTimeText, reason, Priority.NORMAL, due);
        return new Handoff(CALLBACK_TASK, confirmed, cb.getId(), cb.getDueAt());
    }

    private CallbackRequest task(VoiceCall call, Student student, String reason, String preferredTimeText,
                                 String summary, Priority priority, Instant dueAt) {
        AppUser owner = owner(call, student);
        CallbackRequest cb = callbacks.save(new CallbackRequest(call.getStudentId(), call.getLeadId(), call.getId(),
                call.getPhone(), reason, cut(preferredTimeText, 200), cut(summary, 2000), priority, owner, dueAt));
        notifyStaff(call, student, priority, "Call back by " + VoiceFacts.when(dueAt) + ": " + label(call, student),
                (preferredTimeText == null || preferredTimeText.isBlank() ? "" : "Asked for: " + preferredTimeText + ". ")
                        + (summary == null ? "" : summary), "/voice/callbacks", "VCB:" + cb.getId());
        return cb;
    }

    /** Tells the family's counsellor (or the admins) something they should know about this call. */
    @Transactional
    public void inform(VoiceCall call, Priority priority, String title, String body) {
        Student student = call.getStudentId() == null ? null : students.findById(call.getStudentId()).orElse(null);
        notifyStaff(call, student, priority, title + ": " + label(call, student), body, "/voice/calls/" + call.getId(),
                "VINF:" + call.getId());
    }

    private void notifyStaff(VoiceCall call, Student student, Priority priority, String title, String body,
                             String link, String dedupKey) {
        AppUser owner = owner(call, student);
        List<Long> recipients = owner != null ? List.of(owner.getId())
                : users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN)).stream()
                        .map(AppUser::getId).toList();
        for (Long recipient : recipients) {
            alerts.notifyUser(recipient, call.getStudentId(), "VOICE_CALLBACK", priority, title, body, link, dedupKey);
        }
    }

    /** The counsellor who already looks after this family, if they are still active. */
    private AppUser owner(VoiceCall call, Student student) {
        AppUser owner = student != null ? student.getAssignedCounsellor()
                : call.getLeadId() == null ? null
                : leads.findById(call.getLeadId()).map(Lead::getAssignedCounsellor).orElse(null);
        return owner != null && owner.isActive() ? owner : null;
    }

    private String personName(VoiceCall call, Student student) {
        if (student != null) {
            return student.getFullName();
        }
        return call.getLeadId() == null ? null : leads.findById(call.getLeadId()).map(Lead::getFullName).orElse(null);
    }

    private String label(VoiceCall call, Student student) {
        String name = personName(call, student);
        return name == null ? "caller " + mask(call.getPhone()) : name;
    }

    // ------------------------------------------------------------------ due times (spec 18.13 SLAs)

    /**
     * Distress: within 15 minutes whatever the hour. Decision, refund and dispute calls: 30 minutes of desk
     * time. Everything else: two hours of desk time.
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
        ZonedDateTime t = now.atZone(VoiceFacts.IST);
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
        return "A counsellor will call you back by " + VoiceFacts.when(due) + ".";
    }

    static String mask(String phone) {
        return phone == null || phone.length() <= 4 ? "****" : "******" + phone.substring(phone.length() - 4);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
