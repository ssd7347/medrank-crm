package com.mbbscrm.crm.engagement;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.common.ActivityType;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.engagement.CallLog.Direction;
import com.mbbscrm.crm.engagement.CallLog.Outcome;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadActivity;
import com.mbbscrm.crm.lead.LeadActivityRepository;
import com.mbbscrm.crm.lead.LeadService;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Click-to-call logging (spec 4.21). The "Call" button opens the phone's dialer and then asks for the
 * outcome, so every attempt is recorded against the lead or student even when nobody writes a note.
 * A call to a lead also appears in that lead's activity timeline.
 */
@Service
public class CallService {

    private final CallLogRepository calls;
    private final LeadService leads;
    private final LeadActivityRepository activities;
    private final StudentService students;
    private final AppUserRepository users;

    public CallService(CallLogRepository calls, LeadService leads, LeadActivityRepository activities,
                       StudentService students, AppUserRepository users) {
        this.calls = calls;
        this.leads = leads;
        this.activities = activities;
        this.students = students;
        this.users = users;
    }

    /** Exactly one of {@code leadId} / {@code studentId}. {@code phone} defaults to the record's own number. */
    public record CallRequest(Long leadId, Long studentId, @Size(max = 20) String phone, Direction direction,
                              @NotNull Outcome outcome, @Min(0) @Max(14_400) Integer durationSeconds,
                              @Size(max = 2000) String notes) {
    }

    public record CallView(Long id, String phone, Direction direction, Outcome outcome, Integer durationSeconds,
                           String notes, String provider, String recordingUrl, UserRef calledBy, Instant calledAt) {
        static CallView of(CallLog c) {
            return new CallView(c.getId(), c.getPhone(), c.getDirection(), c.getOutcome(), c.getDurationSeconds(),
                    c.getNotes(), c.getProvider(), c.getRecordingUrl(), UserRef.of(c.getCalledBy()), c.getCalledAt());
        }
    }

    @Transactional
    public CallView log(CallRequest req) {
        if ((req.leadId() == null) == (req.studentId() == null)) {
            throw ApiException.badRequest("A call is logged against either a lead or a student");
        }
        CurrentUser me = CurrentUser.get();
        AppUser caller = users.getReferenceById(me.id());
        String notes = req.notes() == null || req.notes().isBlank() ? null : req.notes().trim();
        Direction direction = req.direction() == null ? Direction.OUTBOUND : req.direction();
        String recordPhone;
        if (req.leadId() != null) {
            Lead lead = leads.requireVisible(req.leadId());
            recordPhone = lead.getPhone();
        } else {
            Student s = students.requireReadable(req.studentId());
            recordPhone = s.getPhone();
        }
        String phone = req.phone() == null || req.phone().isBlank() ? recordPhone : Phones.normalize(req.phone());
        CallLog call = calls.save(new CallLog(req.leadId(), req.studentId(), phone, direction, req.outcome(),
                req.durationSeconds(), notes, caller));
        if (req.leadId() != null) {
            activities.save(new LeadActivity(req.leadId(), ActivityType.CALL, describe(req.outcome(), direction),
                    notes, caller));
        }
        return CallView.of(call);
    }

    @Transactional(readOnly = true)
    public List<CallView> forLead(Long leadId) {
        leads.requireVisible(leadId);
        return calls.findByLeadIdOrderByCalledAtDesc(leadId).stream().map(CallView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<CallView> forStudent(Long studentId) {
        students.requireReadable(studentId);
        return calls.findByStudentIdOrderByCalledAtDesc(studentId).stream().map(CallView::of).toList();
    }

    private static String describe(Outcome outcome, Direction direction) {
        String text = outcome.name().charAt(0) + outcome.name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        return direction == Direction.INBOUND ? "Incoming call: " + text : text;
    }
}
