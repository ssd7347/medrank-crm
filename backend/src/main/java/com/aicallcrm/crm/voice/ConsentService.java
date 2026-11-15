package com.aicallcrm.crm.voice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactService;
import com.aicallcrm.crm.contact.ImportService;
import com.aicallcrm.crm.privacy.ErasedNumberRepository;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.voice.Voice.ConsentSource;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Consent to AI calls and to recording, per phone number. Nobody is ever dialled by the AI without a current
 * "yes" for that exact number, and "stop calling me" takes effect at once for every campaign.
 *
 * <p>A number whose owner was erased after saying no (see {@code privacy.ErasureService}) stays opted out: a
 * consent from an imported list does not count for it, only one recorded by hand on the contact page afterwards.
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
    private final ErasedNumberRepository erased;

    static final String ERASED_NOTE = "This number belonged to a person whose data was erased after they said no to "
            + "AI calls. Consent from an imported list does not count for it; only consent recorded here by hand.";

    public ConsentService(ContactConsentRepository consents, ContactService contacts, AuditService audit,
                          ErasedNumberRepository erased) {
        this.consents = consents;
        this.contacts = contacts;
        this.audit = audit;
        this.erased = erased;
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

    /**
     * "These people agreed to AI calls" ticked on a spreadsheet import, with the proof the importer gave. A number
     * that had said no (opted out or refused) keeps its no: a list cannot overrule what one person asked for, only a
     * consent recorded by hand on their page can. Returns false then, and nothing is recorded.
     */
    @Override
    public boolean recordFromImport(Contact contact, boolean recording, String evidence, Long userId) {
        State now = state(contact.getPhone());
        if (now == State.OPTED_OUT || now == State.REFUSED) {
            return false;
        }
        save(contact.getId(), contact.getPhone(), true, recording, ConsentSource.IMPORTED_LIST, evidence, userId);
        return true;
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
        Decision d = decide(phone);
        ContactConsent c = d.consent();
        if (c == null) {
            return d.erased()
                    ? new PhoneConsent(alternate, label, phone, State.OPTED_OUT, false, false, null, null, null, null, ERASED_NOTE)
                    : new PhoneConsent(alternate, label, phone, State.NONE, false, false, null, null, null, null, null);
        }
        return new PhoneConsent(alternate, label, phone, stateOf(c), c.allowsAiCalls(), c.allowsRecording(),
                c.getSource(), c.getEvidenceRef(), c.getCapturedAt(), c.getRevokedAt(), c.getRevokeNote());
    }

    // ------------------------------------------------------------------ used by the agent

    @Transactional(readOnly = true)
    public State state(String phone) {
        Decision d = decide(phone);
        if (d.consent() != null) {
            return stateOf(d.consent());
        }
        return d.erased() ? State.OPTED_OUT : State.NONE;
    }

    @Transactional(readOnly = true)
    public boolean allowsRecording(String phone) {
        ContactConsent c = decide(phone).consent();
        return c != null && c.allowsRecording();
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

    /** {@code consent}: the record that decides for the number, or null. {@code erased}: see the class comment. */
    private record Decision(ContactConsent consent, boolean erased) {
    }

    private Decision decide(String phone) {
        if (phone == null || phone.isBlank()) {
            return new Decision(null, false);
        }
        boolean wasErased = erased.forPhone(phone).isPresent();
        ContactConsent c = consents.findByPhoneOrderByCapturedAtDescIdDesc(phone).stream()
                .filter(x -> !wasErased || x.getSource() != ConsentSource.IMPORTED_LIST)
                .findFirst().orElse(null);
        return new Decision(c, wasErased);
    }

    private static State stateOf(ContactConsent c) {
        if (c.getRevokedAt() != null) {
            return State.OPTED_OUT;
        }
        return c.isAiCallConsent() ? State.GRANTED : State.REFUSED;
    }
}
