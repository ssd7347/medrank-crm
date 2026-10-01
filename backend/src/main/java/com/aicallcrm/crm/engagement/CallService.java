package com.aicallcrm.crm.engagement;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.common.Phones;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactService;
import com.aicallcrm.crm.engagement.CallLog.Direction;
import com.aicallcrm.crm.engagement.CallLog.Outcome;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.user.AppUser;
import com.aicallcrm.crm.user.AppUserRepository;
import com.aicallcrm.crm.user.UserDtos.UserRef;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Calls made by staff from their own phone: the "Call" button opens the dialer and then asks for the outcome,
 * so every attempt is recorded. Calls by the AI agent are written to the same log (see VoiceCallService).
 */
@Service
public class CallService {

    /** {@code phone} defaults to the contact's own number. */
    public record CallRequest(@NotNull Long contactId, @Size(max = 20) String phone, Direction direction,
                              @NotNull Outcome outcome, @Min(0) @Max(14_400) Integer durationSeconds,
                              @Size(max = 2000) String notes) {
    }

    public record CallView(Long id, String phone, Direction direction, Outcome outcome, Integer durationSeconds,
                           String notes, String provider, String recordingUrl, UserRef calledBy, Instant calledAt,
                           /** Set for calls made by the AI agent: opens the transcript. */
                           String voiceCallId) {
        static CallView of(CallLog c) {
            return new CallView(c.getId(), c.getPhone(), c.getDirection(), c.getOutcome(), c.getDurationSeconds(),
                    c.getNotes(), c.getProvider(), c.getRecordingUrl(), UserRef.of(c.getCalledBy()), c.getCalledAt(),
                    CallLog.AI_AGENT.equals(c.getProvider()) ? c.getProviderCallId() : null);
        }
    }

    private final CallLogRepository calls;
    private final ContactService contacts;
    private final AppUserRepository users;

    public CallService(CallLogRepository calls, ContactService contacts, AppUserRepository users) {
        this.calls = calls;
        this.contacts = contacts;
        this.users = users;
    }

    @Transactional
    public CallView log(CallRequest req) {
        CurrentUser me = CurrentUser.get();
        AppUser caller = users.getReferenceById(me.id());
        Contact c = contacts.requireVisible(req.contactId());
        String notes = req.notes() == null || req.notes().isBlank() ? null : req.notes().trim();
        Direction direction = req.direction() == null ? Direction.OUTBOUND : req.direction();
        String phone = req.phone() == null || req.phone().isBlank() ? c.getPhone() : Phones.normalize(req.phone());
        CallLog call = calls.save(new CallLog(c.getId(), phone, direction, req.outcome(), req.durationSeconds(), notes,
                caller));
        contacts.logCallActivity(c.getId(), describe(req.outcome(), direction), notes);
        return CallView.of(call);
    }

    @Transactional(readOnly = true)
    public List<CallView> forContact(Long contactId) {
        contacts.requireVisible(contactId);
        return calls.findByContactIdOrderByCalledAtDesc(contactId).stream().map(CallView::of).toList();
    }

    private static String describe(Outcome outcome, Direction direction) {
        String text = outcome.name().charAt(0) + outcome.name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        return direction == Direction.INBOUND ? "Incoming call: " + text : text;
    }

    @RestController
    @RequestMapping("/api")
    static class CallController {

        private final CallService calls;

        CallController(CallService calls) {
            this.calls = calls;
        }

        @PostMapping("/calls")
        @ResponseStatus(HttpStatus.CREATED)
        public CallView log(@Valid @RequestBody CallRequest req) {
            return calls.log(req);
        }

        @GetMapping("/contacts/{contactId}/calls")
        public List<CallView> forContact(@PathVariable Long contactId) {
            return calls.forContact(contactId);
        }
    }
}
