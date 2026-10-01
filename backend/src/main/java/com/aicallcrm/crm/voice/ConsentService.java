package com.aicallcrm.crm.voice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactService;
import com.aicallcrm.crm.contact.ImportService;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.voice.Voice.ConsentSource;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Consent to AI calls and to recording, per phone number. Nobody is ever dialled by the AI without a current
 * "yes" for that exact number, and "stop calling me" takes effect at once for every campaign.
 */
@Service
public class ConsentService implements ImportService.ConsentRecorder {

    /** Where a number stands. OPTED_OUT: they agreed once and later asked us to stop. */
    public enum State {
        NONE, GRANTED, REFUSED, OPTED_OUT
    }

    /** {@code alternate}: the consent is for the contact's alternate number rather than the main one. */
    public record ConsentRequest(boolean alternate, boolean aiCallConsent, boolean recordingConsent,
                                 @NotNull ConsentSource source, @Size(max = 500) String evidenceRef) {
    }

    public record PhoneConsent(boolean alternate, String label, String phone, State state, boolean aiCalls,
                               boolean recording, ConsentSource source, String evidenceRef, Instant capturedAt,
                               Instant revokedAt, String revokeNote) {
    }

    private final ContactConsentRepository consents;
    private final ContactService contacts;
    private final AuditService audit;

    public ConsentService(ContactConsentRepository consents, ContactService contacts, AuditService audit) {
        this.consents = consents;
        this.contacts = contacts;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ staff

    @Transactional(readOnly = true)
    public List<PhoneConsent> forContact(Long contactId) {
        Contact c = contacts.requireVisible(contactId);
        List<PhoneConsent> out = new ArrayList<>();
        out.add(view(false, "Phone", c.getPhone()));
        if (c.getAltPhone() != null && !c.getAltPhone().equals(c.getPhone())) {
            out.add(view(true, "Alternate phone", c.getAltPhone()));
        }
        return out;
    }

    @Transactional
    public List<PhoneConsent> record(Long contactId, ConsentRequest req) {
        Contact c = contacts.requireVisible(contactId);
        String phone = req.alternate() ? c.getAltPhone() : c.getPhone();
        if (phone == null || phone.isBlank()) {
            throw ApiException.badRequest("This contact has no alternate phone number");
        }
        if (req.recordingConsent() && !req.aiCallConsent()) {
            throw ApiException.badRequest("Recording consent only applies when AI calls are allowed");
        }
        Long me = CurrentUser.get().id();
        save(c.getId(), phone, req.aiCallConsent(), req.recordingConsent(), req.source(), req.evidenceRef(), me);
        audit.record(me, "VOICE_CONSENT_RECORDED", "CONTACT", c.getId(),
                "aiCalls=" + req.aiCallConsent() + " recording=" + req.recordingConsent() + " source=" + req.source());
        return forContact(contactId);
    }

    /** "These people agreed to AI calls" ticked on a spreadsheet import, with the proof the importer gave. */
    @Override
    public void recordFromImport(Contact contact, boolean recording, String evidence, Long userId) {
        save(contact.getId(), contact.getPhone(), true, recording, ConsentSource.IMPORTED_LIST, evidence, userId);
    }

    private void save(Long contactId, String phone, boolean ai, boolean recording, ConsentSource source,
                      String evidence, Long userId) {
        if (!ai) {
            // A "no" also cancels any earlier "yes" for this number.
            consents.findByPhoneOrderByCapturedAtDescIdDesc(phone).forEach(c -> c.revoke("Withdrawn, recorded by staff"));
        }
        String proof = evidence == null || evidence.isBlank() ? null : evidence.trim();
        consents.save(new ContactConsent(contactId, phone, ai, ai && recording, source, proof, userId));
    }

    private PhoneConsent view(boolean alternate, String label, String phone) {
        ContactConsent c = latest(phone).orElse(null);
        if (c == null) {
            return new PhoneConsent(alternate, label, phone, State.NONE, false, false, null, null, null, null, null);
        }
        return new PhoneConsent(alternate, label, phone, stateOf(c), c.allowsAiCalls(), c.allowsRecording(),
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
     * "Please stop calling me", said on a call. Every consent for the number is revoked; if there was none,
     * the refusal itself is recorded so a later campaign cannot treat the number as new.
     */
    @Transactional
    public void optOut(String phone, Long contactId, String note) {
        List<ContactConsent> existing = consents.findByPhoneOrderByCapturedAtDescIdDesc(phone);
        existing.forEach(c -> c.revoke(note));
        if (existing.isEmpty() && contactId != null) {
            ContactConsent refusal = new ContactConsent(contactId, phone, false, false, ConsentSource.VERBAL_ON_CALL,
                    note, null);
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
