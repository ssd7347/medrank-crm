package com.mbbscrm.crm.voice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadService;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.voice.Voice.ConsentSource;
import com.mbbscrm.crm.voice.Voice.PersonType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Consent to AI calls and to recording (spec 18.14.2). Nobody is ever dialled without a current "yes" for
 * that exact number, and a "stop calling me" takes effect at once for every campaign.
 */
@Service
public class ConsentService {

    /** Where a number stands. OPTED_OUT: they agreed once and later asked us to stop. */
    public enum State {
        NONE, GRANTED, REFUSED, OPTED_OUT
    }

    public record ConsentRequest(@NotNull PersonType personType, boolean aiCallConsent, boolean recordingConsent,
                                 @NotNull ConsentSource source, @Size(max = 500) String evidenceRef) {
    }

    public record PhoneConsent(PersonType personType, String label, String phone, State state, boolean aiCalls,
                               boolean recording, ConsentSource source, String evidenceRef, Instant capturedAt,
                               Instant revokedAt, String revokeNote) {
    }

    private final ContactConsentRepository consents;
    private final StudentService students;
    private final LeadService leads;
    private final AuditService audit;

    public ConsentService(ContactConsentRepository consents, StudentService students, LeadService leads,
                          AuditService audit) {
        this.consents = consents;
        this.students = students;
        this.leads = leads;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ staff

    @Transactional(readOnly = true)
    public List<PhoneConsent> forStudent(Long studentId) {
        Student s = students.requireReadable(studentId);
        List<PhoneConsent> out = new ArrayList<>();
        out.add(view(PersonType.STUDENT, "Student", s.getPhone()));
        if (s.getParentPhone() != null && !s.getParentPhone().equals(s.getPhone())) {
            out.add(view(PersonType.PARENT, s.getParentName() == null ? "Parent" : "Parent (" + s.getParentName() + ")",
                    s.getParentPhone()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<PhoneConsent> forLead(Long leadId) {
        Lead l = leads.requireVisible(leadId);
        return List.of(view(PersonType.LEAD, "Lead", l.getPhone()));
    }

    @Transactional
    public List<PhoneConsent> recordForStudent(Long studentId, ConsentRequest req) {
        Student s = students.requireReadable(studentId);
        String phone = switch (req.personType()) {
            case STUDENT -> s.getPhone();
            case PARENT -> s.getParentPhone();
            case LEAD -> throw ApiException.badRequest("Choose the student or the parent");
        };
        if (phone == null || phone.isBlank()) {
            throw ApiException.badRequest("No phone number is recorded for the parent");
        }
        save(req, s.getId(), phone);
        audit.record(CurrentUser.get().id(), "VOICE_CONSENT_RECORDED", "STUDENT", s.getId(), describe(req));
        return forStudent(studentId);
    }

    @Transactional
    public List<PhoneConsent> recordForLead(Long leadId, ConsentRequest req) {
        Lead l = leads.requireVisible(leadId);
        save(new ConsentRequest(PersonType.LEAD, req.aiCallConsent(), req.recordingConsent(), req.source(),
                req.evidenceRef()), l.getId(), l.getPhone());
        audit.record(CurrentUser.get().id(), "VOICE_CONSENT_RECORDED", "LEAD", l.getId(), describe(req));
        return forLead(leadId);
    }

    private void save(ConsentRequest req, Long personId, String phone) {
        if (req.recordingConsent() && !req.aiCallConsent()) {
            throw ApiException.badRequest("Recording consent only applies when AI calls are allowed");
        }
        if (!req.aiCallConsent()) {
            // A "no" recorded by staff also cancels any earlier "yes" for this number.
            consents.findByPhoneOrderByCapturedAtDescIdDesc(phone).forEach(c -> c.revoke("Withdrawn, recorded by staff"));
        }
        String evidence = req.evidenceRef() == null || req.evidenceRef().isBlank() ? null : req.evidenceRef().trim();
        consents.save(new ContactConsent(req.personType(), personId, phone, req.aiCallConsent(),
                req.recordingConsent(), req.source(), evidence, CurrentUser.get().id()));
    }

    private static String describe(ConsentRequest req) {
        return req.personType() + " aiCalls=" + req.aiCallConsent() + " recording=" + req.recordingConsent()
                + " source=" + req.source();
    }

    private PhoneConsent view(PersonType type, String label, String phone) {
        ContactConsent c = latest(phone).orElse(null);
        if (c == null) {
            return new PhoneConsent(type, label, phone, State.NONE, false, false, null, null, null, null, null);
        }
        return new PhoneConsent(type, label, phone, stateOf(c), c.allowsAiCalls(), c.allowsRecording(),
                c.getSource(), c.getEvidenceRef(), c.getCapturedAt(), c.getRevokedAt(), c.getRevokeNote());
    }

    // ------------------------------------------------------------------ used by the agent

    @Transactional(readOnly = true)
    public State state(String phone) {
        return latest(phone).map(ConsentService::stateOf).orElse(State.NONE);
    }

    @Transactional(readOnly = true)
    public boolean allowsRecording(String phone) {
        return latest(phone).map(ContactConsent::allowsRecording).orElse(false);
    }

    /**
     * "Please stop calling me", said on a call (spec 18.5.4). Every consent for the number is revoked; if
     * there was none, the refusal itself is recorded so a later campaign cannot treat the number as new.
     */
    @Transactional
    public void optOut(String phone, PersonType personType, Long personId, String note) {
        List<ContactConsent> existing = consents.findByPhoneOrderByCapturedAtDescIdDesc(phone);
        existing.forEach(c -> c.revoke(note));
        if (existing.isEmpty() && personId != null) {
            ContactConsent refusal = new ContactConsent(personType, personId, phone, false, false,
                    ConsentSource.VERBAL_ON_CALL, note, null);
            refusal.revoke(note);
            consents.save(refusal);
        }
    }

    private Optional<ContactConsent> latest(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        return consents.findByPhoneOrderByCapturedAtDescIdDesc(phone).stream().findFirst();
    }

    private static State stateOf(ContactConsent c) {
        if (c.getRevokedAt() != null) {
            return State.OPTED_OUT;
        }
        return c.isAiCallConsent() ? State.GRANTED : State.REFUSED;
    }
}
