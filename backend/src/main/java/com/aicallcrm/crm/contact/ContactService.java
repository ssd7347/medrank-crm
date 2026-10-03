package com.aicallcrm.crm.contact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.branch.BranchRef;
import com.aicallcrm.crm.branch.BranchRepository;
import com.aicallcrm.crm.common.ActivityType;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.common.PageResponse;
import com.aicallcrm.crm.common.Phones;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.user.AppUser;
import com.aicallcrm.crm.user.AppUserRepository;
import com.aicallcrm.crm.user.UserDtos.UserRef;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Contacts, their timeline and follow-ups. Admins and managers see every contact (within their branch if they
 * have one); agents see the contacts assigned to them and the unassigned ones. Anything else looks like "not found".
 */
@Service
public class ContactService {

    // ------------------------------------------------------------------ DTOs

    public record ContactRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^[0-9+ ()-]{8,20}$", message = "invalid phone") String phone,
            @Pattern(regexp = "^([0-9+ ()-]{8,20})?$", message = "invalid phone") String altPhone,
            @Email @Size(max = 160) String email,
            @Size(max = 20) String status,
            @Size(max = 60) String source,
            @Size(max = 2000) String notes,
            Long assignedToId,
            Long branchId,
            Map<String, String> fields) {
    }

    public record ContactListItem(Long id, String fullName, String phone, String email, String status,
                                  String source, UserRef assignedTo, BranchRef branch, Map<String, String> fields,
                                  List<String> tags, Instant createdAt) {
    }

    public record ContactView(Long id, String fullName, String phone, String altPhone, String email,
                              String status, String source, String notes, UserRef assignedTo, BranchRef branch,
                              Long importBatchId, Map<String, String> fields, List<String> tags, Instant createdAt,
                              Instant updatedAt) {
    }

    public record StatusRequest(@NotBlank @Size(max = 20) String status, @Size(max = 1000) String note) {
    }

    public record AssignRequest(Long userId) {
    }

    public record ActivityRequest(@NotNull ActivityType type, @Size(max = 120) String outcome,
                                  @Size(max = 2000) String notes) {
    }

    public record ActivityView(Long id, ActivityType type, String outcome, String notes, UserRef createdBy,
                               Instant createdAt) {
        static ActivityView of(ContactActivity a) {
            return new ActivityView(a.getId(), a.getType(), a.getOutcome(), a.getNotes(), UserRef.of(a.getCreatedBy()),
                    a.getCreatedAt());
        }
    }

    public record FollowUpRequest(@NotNull @Future Instant dueAt, @NotBlank @Size(max = 200) String purpose,
                                  Long assignedToId) {
    }

    public record FollowUpView(Long id, Long contactId, String contactName, UserRef assignedTo, Instant dueAt,
                               String purpose, Instant completedAt) {
        static FollowUpView of(FollowUp f) {
            return new FollowUpView(f.getId(), f.getContact().getId(), f.getContact().getFullName(),
                    UserRef.of(f.getAssignedTo()), f.getDueAt(), f.getPurpose(), f.getCompletedAt());
        }
    }

    /** Filters for the list, the CSV export and campaign audiences. All optional. */
    public record Filter(String q, String status, Long assignedToId, boolean unassignedOnly, Long importBatchId,
                         Long branchId, String tag) {
        public Filter(String q, String status, Long assignedToId, boolean unassignedOnly, Long importBatchId,
                      Long branchId) {
            this(q, status, assignedToId, unassignedOnly, importBatchId, branchId, null);
        }
    }

    public record TagsRequest(@NotNull @Size(max = TagService.MAX_PER_CONTACT) List<String> tags) {
    }

    public enum BulkAction { ASSIGN, STAGE, ADD_TAG, REMOVE_TAG }

    /**
     * One change to many contacts: either the ones ticked ({@code ids}) or everyone matching {@code filter}
     * (the list as filtered on screen). {@code userId} for ASSIGN (null = unassign), {@code stage} for STAGE,
     * {@code tag} for the tag actions.
     */
    public record BulkRequest(@Size(max = MAX_BULK) List<Long> ids, Filter filter, @NotNull BulkAction action,
                              Long userId, @Size(max = 20) String stage, @Size(max = 60) String tag) {
    }

    public record BulkResult(int matched, int changed) {
    }

    public static final int MAX_BULK = 20_000;

    private final ContactRepository contacts;
    private final ContactActivityRepository activities;
    private final FollowUpRepository followUps;
    private final FieldService fields;
    private final AppUserRepository users;
    private final BranchRepository branches;
    private final StageService stages;
    private final TagService tags;
    private final AuditService audit;

    public ContactService(ContactRepository contacts, ContactActivityRepository activities,
                          FollowUpRepository followUps, FieldService fields, AppUserRepository users,
                          BranchRepository branches, StageService stages, TagService tags, AuditService audit) {
        this.tags = tags;
        this.contacts = contacts;
        this.activities = activities;
        this.followUps = followUps;
        this.fields = fields;
        this.users = users;
        this.branches = branches;
        this.stages = stages;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PageResponse<ContactListItem> search(Filter filter, int page, int size) {
        Page<Contact> result = contacts.findAll(spec(CurrentUser.get(), filter),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result, this::listItem);
    }

    /** Every contact matching the filter that the current user may see, newest first (for the CSV export). */
    @Transactional(readOnly = true)
    public List<Contact> all(Filter filter) {
        return contacts.findAll(spec(CurrentUser.get(), filter), Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    Specification<Contact> spec(CurrentUser me, Filter f) {
        Long branch = me.branchScope() != null ? me.branchScope() : f.branchId();
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (branch != null) {
                p.add(cb.equal(root.get("branch").get("id"), branch));
            }
            Join<Contact, AppUser> owner = root.join("assignedTo", JoinType.LEFT);
            if (!me.seesAllContacts()) {
                p.add(cb.or(cb.equal(owner.get("id"), me.id()), cb.isNull(owner.get("id"))));
            }
            if (f.unassignedOnly()) {
                p.add(cb.isNull(owner.get("id")));
            } else if (f.assignedToId() != null) {
                p.add(cb.equal(owner.get("id"), f.assignedToId()));
            }
            if (f.status() != null && !f.status().isBlank()) {
                p.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.importBatchId() != null) {
                p.add(cb.equal(root.get("importBatchId"), f.importBatchId()));
            }
            if (f.tag() != null && !f.tag().isBlank()) {
                // Tags match whatever their case, so "vip" finds "VIP".
                Subquery<Long> tagged = query.subquery(Long.class);
                Root<Contact> other = tagged.from(Contact.class);
                Join<Contact, String> tag = other.join("tags");
                tagged.select(other.get("id")).where(cb.equal(other.get("id"), root.get("id")),
                        cb.equal(cb.lower(tag), f.tag().trim().toLowerCase(Locale.ROOT)));
                p.add(cb.exists(tagged));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String like = "%" + f.q().trim().toLowerCase(Locale.ROOT) + "%";
                String digits = Phones.normalize(f.q().trim()).replaceAll("\\D", "");
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("fullName")), like));
                any.add(cb.like(cb.lower(root.get("email")), like));
                // Custom field values are searched too (customer IDs, plan names...).
                any.add(cb.like(cb.lower(root.get("fields")), like));
                if (digits.length() >= 3) {
                    any.add(cb.like(root.get("phone"), "%" + digits + "%"));
                }
                p.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    @Transactional(readOnly = true)
    public ContactView get(Long id) {
        return view(requireVisible(id));
    }

    /** Loads a contact the current user may see; anything else looks like "not found". */
    @Transactional(readOnly = true)
    public Contact requireVisible(Long id) {
        CurrentUser me = CurrentUser.get();
        Contact c = contacts.findById(id).orElseThrow(() -> ApiException.notFound("Contact"));
        if (me.outsideBranch(c.getBranch())) {
            throw ApiException.notFound("Contact");
        }
        if (!me.seesAllContacts() && c.getAssignedTo() != null && !c.getAssignedTo().getId().equals(me.id())) {
            throw ApiException.notFound("Contact");
        }
        return c;
    }

    // ------------------------------------------------------------------ changes

    @Transactional
    public ContactView create(ContactRequest req) {
        CurrentUser me = CurrentUser.get();
        String phone = phone(req.phone());
        contacts.findByPhone(phone).ifPresent(other -> {
            throw ApiException.conflict("A contact with this phone number already exists")
                    .with("existingContactId", other.getId());
        });
        Contact c = new Contact();
        c.setCreatedBy(me.id());
        apply(c, req, phone, me);
        if (c.getAssignedTo() == null && !me.seesAllContacts()) {
            c.setAssignedTo(users.getReferenceById(me.id()));
        }
        contacts.save(c);
        audit.record(me.id(), "CONTACT_CREATED", "CONTACT", c.getId(), null);
        return view(c);
    }

    @Transactional
    public ContactView update(Long id, ContactRequest req) {
        CurrentUser me = CurrentUser.get();
        Contact c = requireVisible(id);
        String phone = phone(req.phone());
        contacts.findByPhone(phone).filter(o -> !o.getId().equals(id)).ifPresent(other -> {
            throw ApiException.conflict("Another contact already has this phone number")
                    .with("existingContactId", other.getId());
        });
        String before = c.getStatus();
        apply(c, req, phone, me);
        if (!before.equals(c.getStatus())) {
            activities.save(new ContactActivity(c.getId(), ActivityType.STATUS_CHANGE,
                    label(before) + " → " + label(c.getStatus()), null, users.getReferenceById(me.id())));
        }
        audit.record(me.id(), "CONTACT_UPDATED", "CONTACT", c.getId(), null);
        return view(c);
    }

    private void apply(Contact c, ContactRequest req, String phone, CurrentUser me) {
        c.setFullName(req.fullName().trim());
        c.setPhone(phone);
        c.setAltPhone(req.altPhone() == null || req.altPhone().isBlank() ? null : phone(req.altPhone()));
        c.setEmail(blankToNull(req.email()));
        if (req.status() != null && !req.status().isBlank()) {
            c.setStatus(stages.require(req.status()));
        }
        c.setSource(blankToNull(req.source()));
        c.setNotes(blankToNull(req.notes()));
        fields.write(c, req.fields(), false);

        Long current = c.getAssignedTo() == null ? null : c.getAssignedTo().getId();
        if (req.assignedToId() != null && !req.assignedToId().equals(current)) {
            if (!me.seesAllContacts() && !req.assignedToId().equals(me.id())) {
                throw ApiException.forbidden("Only an admin or a manager can give a contact to someone else");
            }
            c.setAssignedTo(activeUser(req.assignedToId()));
        } else if (req.assignedToId() == null && current != null && me.seesAllContacts()) {
            c.setAssignedTo(null);
        }
        Long scope = me.branchScope();
        if (scope != null) {
            c.setBranch(branches.getReferenceById(scope));
        } else {
            c.setBranch(req.branchId() == null ? null : branches.findById(req.branchId())
                    .orElseThrow(() -> ApiException.badRequest("Branch not found")));
        }
    }

    @Transactional
    public ContactView changeStatus(Long id, StatusRequest req) {
        CurrentUser me = CurrentUser.get();
        Contact c = requireVisible(id);
        String to = stages.require(req.status());
        if (!c.getStatus().equals(to)) {
            activities.save(new ContactActivity(c.getId(), ActivityType.STATUS_CHANGE,
                    label(c.getStatus()) + " → " + label(to), blankToNull(req.note()),
                    users.getReferenceById(me.id())));
            c.setStatus(to);
            audit.record(me.id(), "CONTACT_STATUS", "CONTACT", c.getId(), to);
        }
        return view(c);
    }

    /** Used by the voice agent: "interested", "do not call" and so on, said on an AI call. */
    @Transactional
    public void statusFromCall(Long contactId, String status, String why) {
        contacts.findById(contactId).filter(c -> !c.getStatus().equals(status)).ifPresent(c -> {
            activities.save(new ContactActivity(c.getId(), ActivityType.STATUS_CHANGE,
                    label(c.getStatus()) + " → " + label(status), why, null));
            c.setStatus(status);
        });
    }

    /** Used by the voice agent to put a call on the contact's timeline. */
    @Transactional
    public void logCallActivity(Long contactId, String outcome, String notes) {
        activities.save(new ContactActivity(contactId, ActivityType.CALL, outcome, notes, null));
    }

    @Transactional
    public ContactView assign(Long id, AssignRequest req) {
        CurrentUser me = CurrentUser.get();
        if (!me.seesAllContacts()) {
            throw ApiException.forbidden("Only an admin or a manager can assign contacts");
        }
        Contact c = requireVisible(id);
        AppUser to = req.userId() == null ? null : activeUser(req.userId());
        c.setAssignedTo(to);
        activities.save(new ContactActivity(c.getId(), ActivityType.ASSIGNMENT,
                to == null ? "Unassigned" : "Assigned to " + to.getFullName(), null, users.getReferenceById(me.id())));
        audit.record(me.id(), "CONTACT_ASSIGNED", "CONTACT", c.getId(), to == null ? null : "to=" + to.getId());
        return view(c);
    }

    @Transactional
    public ContactView setTags(Long id, TagsRequest req) {
        CurrentUser me = CurrentUser.get();
        Contact c = requireVisible(id);
        TagService.set(c, req.tags(), tags.spellings());
        audit.record(me.id(), "CONTACT_TAGGED", "CONTACT", c.getId(), null);
        return view(c);
    }

    /** Assign, move to a stage, or tag many contacts at once. Admins and managers only. */
    @Transactional
    public BulkResult bulk(BulkRequest req) {
        CurrentUser me = CurrentUser.get();
        if (!me.seesAllContacts()) {
            throw ApiException.forbidden("Only an admin or a manager can change many contacts at once");
        }
        boolean byIds = req.ids() != null && !req.ids().isEmpty();
        if (!byIds && req.filter() == null) {
            throw ApiException.badRequest("Choose the contacts to change");
        }
        Specification<Contact> which = spec(me, byIds ? new Filter(null, null, null, false, null, null) : req.filter());
        if (byIds) {
            which = which.and((root, query, cb) -> root.get("id").in(req.ids()));
        }
        List<Contact> list = contacts.findAll(which);
        if (list.size() > MAX_BULK) {
            throw ApiException.badRequest("That is " + list.size() + " contacts; narrow the list to " + MAX_BULK
                    + " or fewer");
        }
        AppUser by = users.getReferenceById(me.id());
        int changed = 0;
        switch (req.action()) {
            case ASSIGN -> {
                AppUser to = req.userId() == null ? null : activeUser(req.userId());
                for (Contact c : list) {
                    Long current = c.getAssignedTo() == null ? null : c.getAssignedTo().getId();
                    if (!java.util.Objects.equals(current, req.userId())) {
                        c.setAssignedTo(to);
                        activities.save(new ContactActivity(c.getId(), ActivityType.ASSIGNMENT,
                                to == null ? "Unassigned" : "Assigned to " + to.getFullName(), null, by));
                        changed++;
                    }
                }
            }
            case STAGE -> {
                String stage = stages.require(req.stage());
                String toLabel = stages.label(stage);
                for (Contact c : list) {
                    if (!c.getStatus().equals(stage)) {
                        activities.save(new ContactActivity(c.getId(), ActivityType.STATUS_CHANGE,
                                label(c.getStatus()) + " → " + toLabel, null, by));
                        c.setStatus(stage);
                        changed++;
                    }
                }
            }
            case ADD_TAG, REMOVE_TAG -> {
                String tag = TagService.clean(req.tag());
                if (tag == null) {
                    throw ApiException.badRequest("Type the tag");
                }
                Map<String, String> spellings = tags.spellings();
                for (Contact c : list) {
                    boolean had = TagService.has(c, tag);
                    if (req.action() == BulkAction.ADD_TAG && !had) {
                        TagService.add(c, List.of(tag), spellings);
                        changed++;
                    } else if (req.action() == BulkAction.REMOVE_TAG && had) {
                        TagService.remove(c, List.of(tag));
                        changed++;
                    }
                }
            }
        }
        audit.record(me.id(), "CONTACTS_BULK_" + req.action().name(), "CONTACT", null,
                "matched=" + list.size() + " changed=" + changed);
        return new BulkResult(list.size(), changed);
    }

    // ------------------------------------------------------------------ timeline and follow-ups

    @Transactional(readOnly = true)
    public List<ActivityView> activities(Long id) {
        requireVisible(id);
        return activities.findByContactIdOrderByCreatedAtDesc(id).stream().map(ActivityView::of).toList();
    }

    @Transactional
    public ActivityView addActivity(Long id, ActivityRequest req) {
        CurrentUser me = CurrentUser.get();
        requireVisible(id);
        if (req.type() == ActivityType.STATUS_CHANGE || req.type() == ActivityType.ASSIGNMENT
                || req.type() == ActivityType.IMPORT || req.type() == ActivityType.MERGE) {
            throw ApiException.badRequest("That kind of entry is recorded automatically");
        }
        return ActivityView.of(activities.save(new ContactActivity(id, req.type(), blankToNull(req.outcome()),
                blankToNull(req.notes()), users.getReferenceById(me.id()))));
    }

    @Transactional(readOnly = true)
    public List<FollowUpView> followUps(Long id) {
        requireVisible(id);
        return followUps.findByContactIdOrderByDueAtAsc(id).stream().map(FollowUpView::of).toList();
    }

    @Transactional
    public FollowUpView addFollowUp(Long id, FollowUpRequest req) {
        CurrentUser me = CurrentUser.get();
        Contact c = requireVisible(id);
        Long assignee = req.assignedToId() != null ? req.assignedToId() : me.id();
        if (!assignee.equals(me.id()) && !me.seesAllContacts()) {
            throw ApiException.forbidden("Only an admin or a manager can schedule follow-ups for someone else");
        }
        return FollowUpView.of(followUps.save(new FollowUp(c, activeUser(assignee), req.dueAt(), req.purpose().trim(),
                me.id())));
    }

    @Transactional
    public FollowUpView completeFollowUp(Long followUpId) {
        CurrentUser me = CurrentUser.get();
        FollowUp f = followUps.findById(followUpId).orElseThrow(() -> ApiException.notFound("Follow-up"));
        if (!me.seesAllContacts() && !f.getAssignedTo().getId().equals(me.id())) {
            throw ApiException.notFound("Follow-up");
        }
        if (f.getCompletedAt() == null) {
            f.complete(me.id());
        }
        return FollowUpView.of(f);
    }

    @Transactional(readOnly = true)
    public List<FollowUpView> myFollowUps(int days) {
        CurrentUser me = CurrentUser.get();
        Instant before = Instant.now().plusSeconds(86_400L * Math.clamp(days, 0, 60));
        return followUps.findOpenForUserDueBefore(me.id(), before).stream().map(FollowUpView::of).toList();
    }

    // ------------------------------------------------------------------ helpers

    ContactListItem listItem(Contact c) {
        return new ContactListItem(c.getId(), c.getFullName(), c.getPhone(), c.getEmail(), c.getStatus(), c.getSource(),
                UserRef.of(c.getAssignedTo()), BranchRef.of(c.getBranch()), fields.read(c),
                TagService.sorted(c.getTags()), c.getCreatedAt());
    }

    ContactView view(Contact c) {
        return new ContactView(c.getId(), c.getFullName(), c.getPhone(), c.getAltPhone(), c.getEmail(), c.getStatus(),
                c.getSource(), c.getNotes(), UserRef.of(c.getAssignedTo()), BranchRef.of(c.getBranch()),
                c.getImportBatchId(), fields.read(c), TagService.sorted(c.getTags()), c.getCreatedAt(), c.getUpdatedAt());
    }

    private AppUser activeUser(Long id) {
        return users.findById(id).filter(AppUser::isActive)
                .orElseThrow(() -> ApiException.badRequest("Staff member not found or inactive"));
    }

    /** Indian mobiles are stored as 10 digits; other numbers keep their country code. */
    static String phone(String raw) {
        String p = Phones.normalize(raw.trim());
        if (p == null || p.replaceAll("\\D", "").length() < 8) {
            throw ApiException.badRequest("Enter a valid phone number");
        }
        return p;
    }

    private String label(String stageKey) {
        return stages.label(stageKey);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
