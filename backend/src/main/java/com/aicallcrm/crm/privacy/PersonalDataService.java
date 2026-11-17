package com.aicallcrm.crm.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactService;
import com.aicallcrm.crm.security.CurrentUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import tools.jackson.databind.ObjectMapper;

/**
 * A copy of everything the CRM holds about one person, for when they ask what is kept about them (an access
 * request). It reads what {@link ErasureService} would delete or anonymise, from the same places and found the same
 * way (their contact, and calls and call-backs from their numbers before they were a contact): the contact and its
 * custom fields and tags, the timeline, follow-ups, call log, consent records, AI calls with their transcripts,
 * call-backs, campaign places, do-not-disturb results, staff alerts about them, and the record's change history.
 * Staff are named, times are Indian time, and stored JSON (custom fields, transcripts) is kept as JSON.
 *
 * <p>Only the admin can download it, and each download is in the audit log. Staff notes and alerts are included, so
 * the admin reads it before sending it on. Not included: anything outside the CRM (backups, server logs, the phone
 * company's records), and import reports that mention the person's name (compliance checklist 2.1).
 */
@Service
public class PersonalDataService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ContactService contacts;
    private final AuditService audit;
    private final EntityManager em;
    private final ObjectMapper mapper;

    public PersonalDataService(ContactService contacts, AuditService audit, EntityManager em, ObjectMapper mapper) {
        this.contacts = contacts;
        this.audit = audit;
        this.em = em;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> export(Long contactId) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only the admin can download a person's data");
        }
        Contact c = contacts.requireVisible(contactId);
        Long id = c.getId();
        List<String> phones = new ArrayList<>(new LinkedHashSet<>(
                c.getAltPhone() == null ? List.of(c.getPhone()) : List.of(c.getPhone(), c.getAltPhone())));
        List<String> hashes = phones.stream().map(ErasedNumberRepository::hash).toList();
        // Their AI calls, including calls from their number before it was in the CRM (as ErasureService).
        List<UUID> calls = em.createQuery("select v.id from VoiceCall v where v.contactId = :id"
                        + " or (v.contactId is null and v.phone in :phones)", UUID.class)
                .setParameter("id", id).setParameter("phones", phones).getResultList();
        List<String> callLinks = calls.stream().map(u -> "/voice/calls/" + u).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> about = new LinkedHashMap<>();
        about.put("what", "Everything this CRM holds about this person, from every part of it. Times are Indian time.");
        about.put("exported_at", time(Instant.now()));
        about.put("exported_by", staff(me.id()));
        about.put("not_included", "Copies outside the CRM: database backups, server logs, the phone company's own"
                + " records of the calls, spreadsheets exported earlier, and the audio of recorded AI calls (each call"
                + " below says whether a recording is kept; the admin can play it and give a copy on request).");
        out.put("about_this_copy", about);

        Map<String, Object> contact = one(rows("select c.full_name, c.phone, c.alt_phone, c.email, c.status, c.source,"
                        + " c.notes, c.fields, u.full_name, b.name, c.created_at, c.updated_at from contact c"
                        + " left join app_user u on u.id = c.assigned_to left join branch b on b.id = c.branch_id"
                        + " where c.id = :id",
                List.of("full_name", "phone", "alt_phone", "email", "status", "source", "notes", "custom_fields",
                        "assigned_to", "branch", "created_at", "updated_at"), q -> q.setParameter("id", id)));
        contact.put("tags", em.createNativeQuery("select tag from contact_tag where contact_id = :id order by tag")
                .setParameter("id", id).getResultList());
        out.put("contact", contact);

        out.put("timeline", rows("select a.type, a.outcome, a.notes, u.full_name, a.created_at from contact_activity a"
                        + " left join app_user u on u.id = a.created_by where a.contact_id = :id order by a.created_at, a.id",
                List.of("type", "outcome", "notes", "by", "at"), q -> q.setParameter("id", id)));
        out.put("follow_ups", rows("select f.purpose, f.due_at, u.full_name, f.completed_at, f.created_at from follow_up f"
                        + " left join app_user u on u.id = f.assigned_to where f.contact_id = :id order by f.due_at, f.id",
                List.of("purpose", "due_at", "assigned_to", "completed_at", "created_at"), q -> q.setParameter("id", id)));
        out.put("call_log", rows("select l.called_at, l.phone, l.direction, l.outcome, l.duration_seconds, l.notes,"
                        + " l.provider, u.full_name from call_log l left join app_user u on u.id = l.called_by"
                        + " where l.contact_id = :id order by l.called_at, l.id",
                List.of("at", "phone", "direction", "outcome", "duration_seconds", "notes", "provider", "by"),
                q -> q.setParameter("id", id)));
        out.put("consent_records", rows("select k.phone, k.ai_call_consent, k.recording_consent, k.source,"
                        + " k.evidence_ref, u.full_name, k.captured_at, k.revoked_at, k.revoke_note from contact_consent k"
                        + " left join app_user u on u.id = k.captured_by where k.contact_id = :id or k.phone in (:phones)"
                        + " order by k.captured_at, k.id",
                List.of("phone", "agreed_to_ai_calls", "agreed_to_recording", "source", "evidence", "recorded_by",
                        "recorded_at", "withdrawn_at", "withdrawn_note"),
                q -> q.setParameter("id", id).setParameter("phones", phones)));
        out.put("ai_calls", calls.isEmpty() ? List.of() : rows("select v.created_at, v.started_at, v.ended_at,"
                        + " v.direction, v.phone, v.script_name, v.status, v.test_call, v.duration_sec, v.outcome,"
                        + " v.outcome_note, v.handoff_reason, v.summary, v.transcript, v.flagged_wrong, v.flag_note,"
                        + " case when v.recording_ref is null then false else true end"
                        + " from voice_call v where v.id in (:calls) order by v.created_at",
                List.of("created_at", "started_at", "ended_at", "direction", "phone", "about", "status", "test_call",
                        "duration_seconds", "outcome", "outcome_note", "handed_to_a_person_because", "summary",
                        "transcript", "flagged_wrong", "flag_note", "recording_kept"),
                q -> q.setParameter("calls", calls)));
        out.put("call_backs", rows("select r.created_at, r.reason, r.preferred_time_text, r.summary, r.priority, r.status,"
                        + " u.full_name, r.due_at, r.done_at, r.done_note from callback_request r"
                        + " left join app_user u on u.id = r.assigned_to where r.contact_id = :id"
                        + " or (r.contact_id is null and r.phone in (:phones))"
                        + (calls.isEmpty() ? "" : " or r.voice_call_id in (:calls)") + " order by r.created_at, r.id",
                List.of("created_at", "reason", "asked_for", "summary", "priority", "status", "assigned_to", "due_at",
                        "done_at", "done_note"),
                q -> {
                    q.setParameter("id", id).setParameter("phones", phones);
                    if (!calls.isEmpty()) {
                        q.setParameter("calls", calls);
                    }
                }));
        out.put("campaigns", rows("select g.name, t.phone, t.status, t.skip_reason, t.attempts, t.updated_at"
                        + " from voice_campaign_target t join voice_campaign g on g.id = t.campaign_id"
                        + " where t.contact_id = :id order by t.updated_at, t.id",
                List.of("campaign", "phone", "status", "not_called_because", "attempts", "updated_at"),
                q -> q.setParameter("id", id)));
        out.put("do_not_disturb_checks", rows("select d.result, d.checked_at from dnd_check d"
                        + " where d.phone_hash in (:hashes) order by d.checked_at, d.id",
                List.of("result", "checked_at"), q -> q.setParameter("hashes", hashes)));
        // One alert goes to several staff; each is listed once.
        out.put("staff_alerts", rows("select distinct n.title, n.body, n.created_at from notification n"
                        + " where n.contact_id = :id or n.link = :contactLink"
                        + (callLinks.isEmpty() ? "" : " or n.link in (:callLinks)") + " order by n.created_at",
                List.of("title", "body", "at"),
                q -> {
                    q.setParameter("id", id).setParameter("contactLink", "/contacts/" + id);
                    if (!callLinks.isEmpty()) {
                        q.setParameter("callLinks", callLinks);
                    }
                }));
        out.put("changes_to_their_record", rows("select l.action, l.details, u.full_name, l.created_at from audit_log l"
                        + " left join app_user u on u.id = l.actor_id where l.entity_type = 'CONTACT' and l.entity_id = :id"
                        + " order by l.created_at, l.id",
                List.of("action", "details", "by", "at"), q -> q.setParameter("id", id)));

        audit.record(me.id(), "CONTACT_DATA_EXPORTED", "CONTACT", id, null);
        return out;
    }

    private String staff(Long userId) {
        if (userId == null) {
            return null;
        }
        List<?> names = em.createNativeQuery("select full_name from app_user where id = :id")
                .setParameter("id", userId).getResultList();
        return names.isEmpty() ? null : (String) names.get(0);
    }

    private static Map<String, Object> one(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            throw ApiException.notFound("Contact");
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> rows(String sql, List<String> names, Consumer<Query> params) {
        Query q = em.createNativeQuery(sql);
        params.accept(q);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : q.getResultList()) {
            Object[] values = names.size() == 1 ? new Object[] {r} : (Object[]) r;
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                row.put(names.get(i), value(values[i]));
            }
            out.add(row);
        }
        return out;
    }

    /** Times in Indian time; stored JSON (custom fields, transcripts) as JSON; ids as text. */
    private Object value(Object v) {
        if (v instanceof Timestamp t) {
            return time(t.toInstant());
        }
        if (v instanceof Instant i) {
            return time(i);
        }
        if (v instanceof OffsetDateTime o) {
            return time(o.toInstant());
        }
        if (v instanceof ZonedDateTime z) {
            return time(z.toInstant());
        }
        if (v instanceof UUID u) {
            return u.toString();
        }
        if (v instanceof String s) {
            String t = s.trim();
            if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
                try {
                    return mapper.readTree(t);
                } catch (RuntimeException e) {
                    return s;
                }
            }
        }
        return v;
    }

    private static String time(Instant at) {
        return at.truncatedTo(ChronoUnit.SECONDS).atZone(IST).toOffsetDateTime().toString();
    }

    @RestController
    static class PersonalDataController {

        private final PersonalDataService service;

        PersonalDataController(PersonalDataService service) {
            this.service = service;
        }

        /** Downloaded as a file: personal-data-<id>.json. */
        @GetMapping("/api/contacts/{id}/personal-data")
        public ResponseEntity<Map<String, Object>> export(@PathVariable Long id) {
            Map<String, Object> data = service.export(id);
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename("personal-data-" + id + ".json").build().toString()).body(data);
        }
    }
}
