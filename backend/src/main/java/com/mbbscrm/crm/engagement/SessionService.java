package com.mbbscrm.crm.engagement;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.counselling.AlertTexts;
import com.mbbscrm.crm.engagement.CounsellingSession.Mode;
import com.mbbscrm.crm.engagement.CounsellingSession.Status;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Counselling sessions (spec 4.19): schedule a video, phone or in-person consultation from the student
 * profile, invite the family, and keep the notes on the student record.
 *
 * No Zoom/Google account is connected, so a video session without a link gets a Jitsi Meet room with an
 * unguessable name; staff can paste their own Zoom or Meet link instead.
 */
@Service
public class SessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ROOM_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";

    private final CounsellingSessionRepository sessions;
    private final StudentService students;
    private final AppUserRepository users;
    private final AlertService alerts;
    private final AlertTexts texts;
    private final AuditService audit;

    public SessionService(CounsellingSessionRepository sessions, StudentService students, AppUserRepository users,
                          AlertService alerts, AlertTexts texts, AuditService audit) {
        this.sessions = sessions;
        this.students = students;
        this.users = users;
        this.alerts = alerts;
        this.texts = texts;
        this.audit = audit;
    }

    public record CreateRequest(
            @NotNull Mode mode,
            @NotNull Instant scheduledAt,
            @NotNull @Min(10) @Max(240) Integer durationMinutes,
            @NotBlank @Size(max = 200) String topic,
            @Size(max = 500) String meetingUrl,
            Long hostId,
            boolean notifyFamily) {
    }

    public record UpdateRequest(
            @NotNull Status status,
            @NotNull Instant scheduledAt,
            @NotNull @Min(10) @Max(240) Integer durationMinutes,
            @NotBlank @Size(max = 200) String topic,
            @Size(max = 500) String meetingUrl,
            @Size(max = 4000) String notes,
            boolean recordingConsent,
            @Size(max = 500) String recordingUrl) {
    }

    public record SessionView(Long id, Long studentId, String studentName, UserRef host, Mode mode,
                              Instant scheduledAt, int durationMinutes, String topic, String meetingUrl,
                              Status status, String notes, boolean recordingConsent, String recordingUrl) {
        static SessionView of(CounsellingSession s) {
            return new SessionView(s.getId(), s.getStudent().getId(), s.getStudent().getFullName(),
                    UserRef.of(s.getHost()), s.getMode(), s.getScheduledAt(), s.getDurationMinutes(), s.getTopic(),
                    s.getMeetingUrl(), s.getStatus(), s.getNotes(), s.isRecordingConsent(), s.getRecordingUrl());
        }
    }

    @Transactional(readOnly = true)
    public List<SessionView> forStudent(Long studentId) {
        students.requireReadable(studentId);
        return sessions.findByStudentIdOrderByScheduledAtDesc(studentId).stream().map(SessionView::of).toList();
    }

    /** For callers that have already checked access themselves, such as the family portal. */
    @Transactional(readOnly = true)
    public List<SessionView> upcomingForStudentUnchecked(Long studentId) {
        Instant cutoff = Instant.now().minus(2, ChronoUnit.HOURS);
        return sessions.findByStudentIdOrderByScheduledAtDesc(studentId).stream()
                .filter(s -> s.getStatus() == Status.SCHEDULED && s.getScheduledAt().isAfter(cutoff))
                .map(SessionView::of).toList().reversed();
    }

    /** The current user's sessions from yesterday to two weeks ahead. */
    @Transactional(readOnly = true)
    public List<SessionView> mine() {
        Instant now = Instant.now();
        return sessions.findByHostIdAndScheduledAtBetweenOrderByScheduledAtAsc(CurrentUser.get().id(),
                now.minus(1, ChronoUnit.DAYS), now.plus(14, ChronoUnit.DAYS)).stream().map(SessionView::of).toList();
    }

    @Transactional
    public SessionView create(Long studentId, CreateRequest req) {
        Student student = students.requireWritable(studentId);
        CurrentUser me = CurrentUser.get();
        if (req.scheduledAt().isBefore(Instant.now().minus(5, ChronoUnit.MINUTES))) {
            throw ApiException.badRequest("Pick a time in the future");
        }
        Long hostId = req.hostId() != null && me.isAdmin() ? req.hostId() : me.id();
        AppUser host = users.findById(hostId).filter(AppUser::isActive)
                .filter(u -> u.getRole() == Role.SUPER_ADMIN || u.getRole() == Role.COUNSELLOR)
                .orElseThrow(() -> ApiException.badRequest("The host must be an active counsellor or admin"));
        CounsellingSession s = new CounsellingSession();
        s.setStudent(student);
        s.setHost(host);
        s.setMode(req.mode());
        s.setScheduledAt(req.scheduledAt());
        s.setDurationMinutes(req.durationMinutes());
        s.setTopic(req.topic().trim());
        s.setMeetingUrl(req.mode() == Mode.VIDEO ? meetingUrl(req.meetingUrl(), true) : null);
        s.setCreatedBy(me.id());
        sessions.save(s);
        audit.record(me.id(), "SESSION_SCHEDULED", "COUNSELLING_SESSION", s.getId(), s.getMode() + " "
                + s.getScheduledAt());
        if (req.notifyFamily()) {
            alerts.messageFamily(student, "SESSION_INVITE", Priority.NORMAL, texts.sessionInvite(student, s),
                    "SESSION:" + s.getId() + ":INVITE");
        }
        if (!host.getId().equals(me.id())) {
            alerts.notifyUser(host.getId(), student.getId(), "SESSION_SCHEDULED", Priority.NORMAL,
                    "Session with " + student.getFullName(), s.getTopic() + " on " + AlertTexts.when(s.getScheduledAt())
                            + ".", "/students/" + student.getId() + "?tab=sessions", "SESSION:" + s.getId() + ":HOST");
        }
        return SessionView.of(s);
    }

    @Transactional
    public SessionView update(Long id, UpdateRequest req) {
        CounsellingSession s = sessions.findById(id).orElseThrow(() -> ApiException.notFound("Session"));
        students.requireWritable(s.getStudent().getId());
        CurrentUser me = CurrentUser.get();
        Status before = s.getStatus();
        s.setStatus(req.status());
        s.setScheduledAt(req.scheduledAt());
        s.setDurationMinutes(req.durationMinutes());
        s.setTopic(req.topic().trim());
        if (s.getMode() == Mode.VIDEO) {
            s.setMeetingUrl(meetingUrl(req.meetingUrl(), false) == null ? s.getMeetingUrl()
                    : meetingUrl(req.meetingUrl(), false));
        }
        s.setNotes(blankToNull(req.notes()));
        s.setRecordingConsent(req.recordingConsent());
        String recording = blankToNull(req.recordingUrl());
        if (recording != null) {
            if (!req.recordingConsent()) {
                throw ApiException.badRequest("A recording can be saved only if the family agreed to be recorded");
            }
            requireHttps(recording);
        }
        s.setRecordingUrl(recording);
        audit.record(me.id(), "SESSION_UPDATED", "COUNSELLING_SESSION", s.getId(), before + " -> " + s.getStatus());
        return SessionView.of(s);
    }

    private static String meetingUrl(String given, boolean generateIfBlank) {
        String url = blankToNull(given);
        if (url == null) {
            if (!generateIfBlank) {
                return null;
            }
            StringBuilder room = new StringBuilder("counselling-");
            for (int i = 0; i < 16; i++) {
                room.append(ROOM_ALPHABET.charAt(RANDOM.nextInt(ROOM_ALPHABET.length())));
            }
            return "https://meet.jit.si/" + room;
        }
        requireHttps(url);
        return url;
    }

    private static void requireHttps(String url) {
        if (!url.startsWith("https://") || url.contains(" ")) {
            throw ApiException.badRequest("Links must start with https://");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
