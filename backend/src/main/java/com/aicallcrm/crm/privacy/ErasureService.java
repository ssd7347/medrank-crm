package com.aicallcrm.crm.privacy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.common.Phones;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactService;
import com.aicallcrm.crm.contact.ContactStatus;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.voice.ConsentService;
import com.aicallcrm.crm.voice.ConsentService.State;
import com.aicallcrm.crm.voice.Voice.CallStatus;

import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Erases one person from the CRM when they ask for it: the contact and everything about them goes, at once and
 * for good. Their timeline, follow-ups, call log, consent records, campaign places, call-backs, alerts and
 * cached do-not-disturb results are deleted. Their AI calls stay only as numbers for the reports (when, how long,
 * how it ended), with the number, transcript, summary, notes and the phone company's call id removed and no link
 * back to anyone. Import reports that name them have the name replaced, and earlier audit entries about the
 * contact lose their details (the entries themselves stay).
 *
 * <p>If they had said no to AI calls (opted out, refused, or marked "Do not call"), a hash of each such number is
 * kept, so a list imported later cannot make it callable again ({@link ConsentService}). Nothing else is kept.
 *
 * <p>Only the admin can do this, after typing the person's phone number, and not while a call with them is live.
 */
@Service
public class ErasureService {

    static final String SCRUBBED = "[erased]";

    public record EraseRequest(@NotBlank @Size(max = 30) String confirmPhone, @NotBlank @Size(max = 200) String reason) {
    }

    /** What was removed. {@code numbersKeptAsHash}: how many of the person's numbers stay blocked (see above). */
    public record Erased(int timelineEntries, int followUps, int callLogs, int aiCallsAnonymised, int consentRecords,
                         int callbacks, int alerts, int campaignPlaces, int importReportsCleaned,
                         int numbersKeptAsHash) {
    }

    private final ContactService contacts;
    private final ConsentService consent;
    private final ErasedNumberRepository erased;
    private final AuditService audit;
    private final EntityManager em;
    private final ObjectMapper mapper;

    public ErasureService(ContactService contacts, ConsentService consent, ErasedNumberRepository erased,
                          AuditService audit, EntityManager em, ObjectMapper mapper) {
        this.contacts = contacts;
        this.consent = consent;
        this.erased = erased;
        this.audit = audit;
        this.em = em;
        this.mapper = mapper;
    }

    @Transactional
    public Erased erase(Long contactId, EraseRequest req) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only the admin can erase a person's data");
        }
        Contact c = contacts.requireVisible(contactId);
        String typed = Phones.normalize(req.confirmPhone().trim());
        if (typed == null || !typed.equals(c.getPhone())) {
            throw ApiException.badRequest("The number typed is not this contact's phone number");
        }
        Long id = c.getId();
        String name = c.getFullName();
        List<String> phones = new ArrayList<>(new LinkedHashSet<>(
                c.getAltPhone() == null ? List.of(c.getPhone()) : List.of(c.getPhone(), c.getAltPhone())));

        Long live = em.createQuery("select count(v) from VoiceCall v where v.testCall = false and v.status in :live"
                        + " and (v.contactId = :id or v.phone in :phones)", Long.class)
                .setParameter("live", List.of(CallStatus.QUEUED, CallStatus.DIALING, CallStatus.CONNECTED))
                .setParameter("id", id).setParameter("phones", phones).getSingleResult();
        if (live > 0) {
            throw ApiException.conflict("An AI call with this person is in progress. Erase them once it has ended.");
        }

        // Decided before anything is deleted: which numbers had said no, and must stay that way.
        boolean doNotCall = ContactStatus.DO_NOT_CALL.equals(c.getStatus());
        Set<String> keep = new LinkedHashSet<>();
        for (String p : phones) {
            State s = consent.state(p);
            if (doNotCall || s == State.OPTED_OUT || s == State.REFUSED) {
                keep.add(ErasedNumberRepository.hash(p));
            }
        }
        List<String> hashes = phones.stream().map(ErasedNumberRepository::hash).toList();

        // Their AI calls, including calls from their number before it was in the CRM.
        List<UUID> calls = em.createQuery("select v.id from VoiceCall v where v.contactId = :id"
                        + " or (v.contactId is null and v.phone in :phones)", UUID.class)
                .setParameter("id", id).setParameter("phones", phones).getResultList();
        List<String> callLinks = calls.stream().map(u -> "/voice/calls/" + u).toList();
        // The phone company's ids for those calls lead to its own records of them (number, time, length).
        List<String> providerIds = calls.isEmpty() ? List.of() : em.createQuery("select v.providerCallId from VoiceCall v"
                        + " where v.id in :calls and v.providerCallId is not null", String.class)
                .setParameter("calls", calls).getResultList();

        em.flush();
        int alerts = update("delete from notification where contact_id = :id or link = :contactLink"
                + (callLinks.isEmpty() ? "" : " or link in (:callLinks)"), q -> {
                    q.setParameter("id", id).setParameter("contactLink", "/contacts/" + id);
                    if (!callLinks.isEmpty()) {
                        q.setParameter("callLinks", callLinks);
                    }
                });
        int callbacks = update("delete from callback_request where contact_id = :id"
                + " or (contact_id is null and phone in (:phones))"
                + (calls.isEmpty() ? "" : " or voice_call_id in (:calls)"), q -> {
                    q.setParameter("id", id).setParameter("phones", phones);
                    if (!calls.isEmpty()) {
                        q.setParameter("calls", calls);
                    }
                });
        int places = update("delete from voice_campaign_target where contact_id = :id", q -> q.setParameter("id", id));
        int consents = update("delete from contact_consent where contact_id = :id or phone in (:phones)",
                q -> q.setParameter("id", id).setParameter("phones", phones));
        update("delete from dnd_check where phone_hash in (:hashes)", q -> q.setParameter("hashes", hashes));
        int anonymised = calls.isEmpty() ? 0 : update("update voice_call set contact_id = null, phone = '',"
                + " transcript = null, summary = null, outcome_note = null, flag_note = null, recording_ref = null,"
                + " provider_call_id = null where id in (:calls)", q -> q.setParameter("calls", calls));
        if (!providerIds.isEmpty()) {
            update("delete from voice_webhook_event where provider_call_id in (:ids)", q -> q.setParameter("ids", providerIds));
        }
        int timeline = update("delete from contact_activity where contact_id = :id", q -> q.setParameter("id", id));
        int followUps = update("delete from follow_up where contact_id = :id", q -> q.setParameter("id", id));
        int callLogs = update("delete from call_log where contact_id = :id", q -> q.setParameter("id", id));
        update("delete from duplicate_dismissal where contact_a = :id or contact_b = :id", q -> q.setParameter("id", id));
        update("delete from contact_tag where contact_id = :id", q -> q.setParameter("id", id));
        em.detach(c);
        update("delete from contact where id = :id", q -> q.setParameter("id", id));
        // Earlier audit entries about this contact said what changed (consent, status); the fact stays, the text goes.
        update("update audit_log set details = null where entity_type = 'CONTACT' and entity_id = :id",
                q -> q.setParameter("id", id));
        int imports = scrubImportReports(name);

        for (String h : keep) {
            if (erased.findByPhoneHash(h).isEmpty()) {
                erased.save(new ErasedNumber(h));
            }
        }
        Erased result = new Erased(timeline, followUps, callLogs, anonymised, consents, callbacks, alerts, places,
                imports, keep.size());
        audit.record(me.id(), "CONTACT_ERASED", "CONTACT", id, "reason=" + req.reason().trim() + "; " + result);
        return result;
    }

    /** Import reports name a person in "Row 7: already in the CRM (Name), not changed". */
    private int scrubImportReports(String name) {
        String needle = "(" + name + ")";
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("select id, problems from import_batch where problems is not null")
                .getResultList();
        int cleaned = 0;
        for (Object[] row : rows) {
            String json = (String) row[1];
            List<String> problems;
            try {
                problems = mapper.readValue(json, new TypeReference<List<String>>() { });
            } catch (RuntimeException e) {
                continue;
            }
            if (problems.stream().noneMatch(p -> p != null && p.contains(needle))) {
                continue;
            }
            List<String> clean = problems.stream()
                    .map(p -> p == null ? null : p.replace(needle, "(" + SCRUBBED + ")")).toList();
            Long batchId = ((Number) row[0]).longValue();
            String out = mapper.writeValueAsString(clean);
            update("update import_batch set problems = :p where id = :id",
                    q -> q.setParameter("p", out).setParameter("id", batchId));
            cleaned++;
        }
        return cleaned;
    }

    private int update(String sql, java.util.function.Consumer<jakarta.persistence.Query> params) {
        jakarta.persistence.Query q = em.createNativeQuery(sql);
        params.accept(q);
        return q.executeUpdate();
    }

    @RestController
    static class ErasureController {

        private final ErasureService service;

        ErasureController(ErasureService service) {
            this.service = service;
        }

        @PostMapping("/api/contacts/{id}/erase")
        @ResponseStatus(HttpStatus.OK)
        public Erased erase(@PathVariable Long id, @Valid @RequestBody EraseRequest req) {
            return service.erase(id, req);
        }
    }
}
