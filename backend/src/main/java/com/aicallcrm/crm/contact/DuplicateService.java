package com.aicallcrm.crm.contact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ActivityType;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.ContactService.ContactView;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.user.AppUserRepository;

import jakarta.persistence.EntityManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Finds contacts that are probably the same person (same email, one's phone is the other's alternate number,
 * or the same full name) and merges them. Spreadsheets from different sources make these all the time.
 * Merging keeps one contact and moves everything of the other onto it: timeline, follow-ups, calls, AI calls,
 * consent, call-backs. "Do not call" always wins, so a merge can never make someone callable again.
 */
@Service
public class DuplicateService {

    public static final int MAX_GROUPS = 100;
    /** A key shared by more contacts than this ("Ravi Kumar") says little; it is not used. */
    static final int MAX_GROUP_SIZE = 10;

    /** Tables whose contact_id moves to the kept contact on a merge. */
    static final List<String> LINKED_TABLES = List.of("contact_activity", "follow_up", "call_log", "notification",
            "contact_consent", "voice_campaign_target", "voice_call", "callback_request");

    public record DuplicateContact(Long id, String fullName, String phone, String altPhone, String email,
                                   String status, List<String> tags, Instant createdAt) {
    }

    public record DuplicateGroup(List<String> reasons, List<DuplicateContact> contacts) {
    }

    public record MergeRequest(@NotNull Long otherId) {
    }

    public record DismissRequest(@NotNull @Size(min = 2, max = MAX_GROUP_SIZE) List<Long> ids) {
    }

    private final ContactRepository contacts;
    private final ContactService contactService;
    private final ContactActivityRepository activities;
    private final FieldService fields;
    private final TagService tags;
    private final AppUserRepository users;
    private final EntityManager em;
    private final AuditService audit;

    DuplicateService(ContactRepository contacts, ContactService contactService, ContactActivityRepository activities,
                     FieldService fields, TagService tags, AppUserRepository users, EntityManager em,
                     AuditService audit) {
        this.contacts = contacts;
        this.contactService = contactService;
        this.activities = activities;
        this.fields = fields;
        this.tags = tags;
        this.users = users;
        this.em = em;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ finding

    @Transactional(readOnly = true)
    public List<DuplicateGroup> find() {
        manager();
        List<Contact> all = contacts.findAll();
        Map<String, List<Contact>> byKey = new HashMap<>();
        for (Contact c : all) {
            for (String k : keys(c)) {
                byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
            }
        }
        Set<Long> dismissed = dismissedPairs();
        // Union-find over contact ids, joining only pairs nobody has said are different people.
        Map<Long, Long> parent = new HashMap<>();
        Map<Long, Set<String>> reasons = new HashMap<>();
        for (Map.Entry<String, List<Contact>> e : byKey.entrySet()) {
            List<Contact> list = e.getValue();
            if (list.size() < 2 || list.size() > MAX_GROUP_SIZE) {
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    long a = list.get(i).getId(), b = list.get(j).getId();
                    if (a == b || dismissed.contains(pair(a, b))) {
                        continue;
                    }
                    long root = union(parent, a, b);
                    reasons.computeIfAbsent(root, x -> new LinkedHashSet<>()).add(reason(e.getKey()));
                }
            }
        }
        Map<Long, List<Contact>> groups = new LinkedHashMap<>();
        Map<Long, Contact> byId = new HashMap<>();
        all.forEach(c -> byId.put(c.getId(), c));
        for (Long id : parent.keySet()) {
            groups.computeIfAbsent(findRoot(parent, id), x -> new ArrayList<>()).add(byId.get(id));
        }
        // Reasons were recorded against roots that may since have been joined into another root.
        Map<Long, Set<String>> groupReasons = new HashMap<>();
        reasons.forEach((root, rs) -> groupReasons.computeIfAbsent(findRoot(parent, root), x -> new LinkedHashSet<>()).addAll(rs));
        return groups.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Long, List<Contact>> e) -> e.getValue().stream()
                        .map(Contact::getCreatedAt).max(Comparator.naturalOrder()).orElse(Instant.EPOCH)).reversed())
                .limit(MAX_GROUPS)
                .map(e -> new DuplicateGroup(List.copyOf(groupReasons.getOrDefault(e.getKey(), Set.of())),
                        e.getValue().stream().sorted(Comparator.comparing(Contact::getCreatedAt))
                                .map(DuplicateService::view).toList()))
                .toList();
    }

    /** What makes two contacts look alike. Phone is unique, so a shared phone means phone = alternate phone. */
    static List<String> keys(Contact c) {
        List<String> k = new ArrayList<>();
        if (c.getEmail() != null && !c.getEmail().isBlank()) {
            k.add("email:" + c.getEmail().trim().toLowerCase(Locale.ROOT));
        }
        k.add("phone:" + c.getPhone());
        if (c.getAltPhone() != null && !c.getAltPhone().isBlank()) {
            k.add("phone:" + c.getAltPhone());
        }
        String name = normalName(c.getFullName());
        if (name != null) {
            k.add("name:" + name);
        }
        return k;
    }

    /** "  Priya   S. Kumar" -> "priya s kumar". Single words ("Ravi") are too common to mean anything. */
    static String normalName(String full) {
        if (full == null) {
            return null;
        }
        String n = full.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
        return n.contains(" ") && n.length() >= 5 ? n : null;
    }

    private static String reason(String key) {
        return key.startsWith("email:") ? "Same email" : key.startsWith("phone:") ? "Same phone number" : "Same name";
    }

    @Transactional
    public void dismiss(DismissRequest req) {
        CurrentUser me = manager();
        List<Long> ids = req.ids().stream().distinct().sorted().toList();
        if (contacts.findAllById(ids).size() != ids.size()) {
            throw ApiException.notFound("Contact");
        }
        Set<Long> already = dismissedPairs();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                if (!already.contains(pair(ids.get(i), ids.get(j)))) {
                    em.createNativeQuery("insert into duplicate_dismissal (contact_a, contact_b, dismissed_by, created_at) "
                                    + "values (?1, ?2, ?3, ?4)")
                            .setParameter(1, ids.get(i)).setParameter(2, ids.get(j)).setParameter(3, me.id())
                            .setParameter(4, Instant.now()).executeUpdate();
                }
            }
        }
        audit.record(me.id(), "DUPLICATES_DISMISSED", "CONTACT", ids.get(0), ids.toString());
    }

    private Set<Long> dismissedPairs() {
        Set<Long> s = new HashSet<>();
        for (Object row : em.createNativeQuery("select contact_a, contact_b from duplicate_dismissal").getResultList()) {
            Object[] r = (Object[]) row;
            s.add(pair(((Number) r[0]).longValue(), ((Number) r[1]).longValue()));
        }
        return s;
    }

    /** One number for an unordered pair of ids (ids stay far below 2^31). */
    private static long pair(long a, long b) {
        return Math.min(a, b) * 2_147_483_648L + Math.max(a, b);
    }

    private static long union(Map<Long, Long> parent, long a, long b) {
        long ra = findRoot(parent, a), rb = findRoot(parent, b);
        if (ra != rb) {
            parent.put(rb, ra);
        }
        return ra;
    }

    private static long findRoot(Map<Long, Long> parent, long x) {
        parent.putIfAbsent(x, x);
        long r = x;
        while (parent.get(r) != r) {
            r = parent.get(r);
        }
        parent.put(x, r);
        return r;
    }

    // ------------------------------------------------------------------ merging

    /** Keeps {@code keepId} and folds {@code req.otherId()} into it. The other contact is deleted. */
    @Transactional
    public ContactView merge(Long keepId, MergeRequest req) {
        CurrentUser me = manager();
        if (keepId.equals(req.otherId())) {
            throw ApiException.badRequest("Choose two different contacts");
        }
        Contact keep = contactService.requireVisible(keepId);
        Contact other = contactService.requireVisible(req.otherId());

        // Details: the kept contact's own values win; blanks are filled from the other.
        if (keep.getAltPhone() == null && !other.getPhone().equals(keep.getPhone())) {
            keep.setAltPhone(other.getPhone());
        }
        List<String> extraPhones = new ArrayList<>();
        for (String p : new String[] {other.getPhone(), other.getAltPhone()}) {
            if (p != null && !p.equals(keep.getPhone()) && !p.equals(keep.getAltPhone())) {
                extraPhones.add(p);
            }
        }
        if (keep.getEmail() == null) {
            keep.setEmail(other.getEmail());
        }
        if (keep.getSource() == null) {
            keep.setSource(other.getSource());
        }
        if (keep.getAssignedTo() == null) {
            keep.setAssignedTo(other.getAssignedTo());
        }
        if (keep.getBranch() == null) {
            keep.setBranch(other.getBranch());
        }
        String notes = join(keep.getNotes(), other.getNotes());
        if (!extraPhones.isEmpty()) {
            notes = join(notes, "Also reachable on " + String.join(", ", extraPhones) + " (from a merged contact).");
        }
        keep.setNotes(notes == null ? null : notes.length() > 2000 ? notes.substring(0, 2000) : notes);
        Map<String, String> mine = fields.read(keep);
        Map<String, String> missing = new LinkedHashMap<>();
        fields.read(other).forEach((k, v) -> {
            if (!mine.containsKey(k)) {
                missing.put(k, v);
            }
        });
        fields.write(keep, missing, true);
        TagService.add(keep, other.getTags(), tags.spellings());
        String note = "Merged with " + other.getFullName() + " (" + other.getPhone() + ")";
        if (ContactStatus.DO_NOT_CALL.equals(other.getStatus()) && !ContactStatus.DO_NOT_CALL.equals(keep.getStatus())) {
            contactService.statusFromCall(keep.getId(), ContactStatus.DO_NOT_CALL,
                    "The merged contact was marked \"Do not call\"");
        }
        em.flush();

        // Everything that belonged to the other contact now belongs to the kept one.
        for (String table : LINKED_TABLES) {
            em.createNativeQuery("update " + table + " set contact_id = ?1 where contact_id = ?2")
                    .setParameter(1, keep.getId()).setParameter(2, other.getId()).executeUpdate();
        }
        em.createNativeQuery("delete from duplicate_dismissal where contact_a = ?1 or contact_b = ?1")
                .setParameter(1, other.getId()).executeUpdate();
        Long otherId = other.getId();
        other.getTags().clear();
        em.flush();
        em.detach(other);
        em.createNativeQuery("delete from contact where id = ?1").setParameter(1, otherId).executeUpdate();

        activities.save(new ContactActivity(keep.getId(), ActivityType.MERGE, note, null, users.getReferenceById(me.id())));
        audit.record(me.id(), "CONTACTS_MERGED", "CONTACT", keep.getId(), "removed=" + otherId);
        return contactService.view(keep);
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null || b.isBlank() ? null : b;
        }
        return b == null || b.isBlank() || a.contains(b) ? a : a + "\n\n" + b;
    }

    private static DuplicateContact view(Contact c) {
        return new DuplicateContact(c.getId(), c.getFullName(), c.getPhone(), c.getAltPhone(), c.getEmail(),
                c.getStatus(), TagService.sorted(c.getTags()), c.getCreatedAt());
    }

    private static CurrentUser manager() {
        CurrentUser me = CurrentUser.get();
        if (!me.seesAllContacts()) {
            throw ApiException.forbidden("Only an admin or a manager can merge contacts");
        }
        return me;
    }

    @RestController
    static class DuplicateController {

        private final DuplicateService service;

        DuplicateController(DuplicateService service) {
            this.service = service;
        }

        @GetMapping("/api/contacts/duplicates")
        public List<DuplicateGroup> find() {
            return service.find();
        }

        @PostMapping("/api/contacts/duplicates/dismiss")
        public void dismiss(@Valid @RequestBody DismissRequest req) {
            service.dismiss(req);
        }

        @PostMapping("/api/contacts/{id}/merge")
        public ContactView merge(@PathVariable Long id, @Valid @RequestBody MergeRequest req) {
            return service.merge(id, req);
        }
    }
}
