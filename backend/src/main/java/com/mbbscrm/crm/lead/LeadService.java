package com.mbbscrm.crm.lead;

import com.mbbscrm.crm.alumni.AlumniService;

import com.mbbscrm.crm.branch.BranchRepository;
import com.mbbscrm.crm.marketing.CampaignRepository;
import com.mbbscrm.crm.student.StudentRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ActivityType;
import com.mbbscrm.crm.fee.CommissionService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.common.PageResponse;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.lead.LeadDtos.ActivityRequest;
import com.mbbscrm.crm.lead.LeadDtos.ActivityResponse;
import com.mbbscrm.crm.lead.LeadDtos.DuplicateRef;
import com.mbbscrm.crm.lead.LeadDtos.FollowUpRequest;
import com.mbbscrm.crm.lead.LeadDtos.FollowUpResponse;
import com.mbbscrm.crm.lead.LeadDtos.LeadListItem;
import com.mbbscrm.crm.lead.LeadDtos.LeadRequest;
import com.mbbscrm.crm.lead.LeadDtos.LeadResponse;
import com.mbbscrm.crm.referral.ReferralAssociateRepository;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentDtos.StudentRequest;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

/**
 * Lead pipeline. Admins see every lead; counsellors and telecallers see leads assigned to them plus
 * unassigned ones (so new inquiries can be picked up).
 */
@Service
public class LeadService {

    static final EnumSet<Role> LEAD_ROLES = EnumSet.of(Role.SUPER_ADMIN, Role.COUNSELLOR, Role.TELECALLER);

    /** Stages that only make sense once the lead has a student profile. */
    private static final EnumSet<LeadStatus> NEEDS_STUDENT =
            EnumSet.of(LeadStatus.ACTIVELY_COUNSELLED, LeadStatus.ADMISSION_CONFIRMED);

    private final LeadRepository leads;
    private final LeadActivityRepository activities;
    private final FollowUpRepository followUps;
    private final AppUserRepository users;
    private final ReferralAssociateRepository associates;
    private final StudentService studentService;
    private final CommissionService commissions;
    private final AuditService audit;
    private final BranchRepository branches;
    private final CampaignRepository campaigns;
    private final StudentRepository students;
    private final AlumniService alumni;

    public LeadService(LeadRepository leads, LeadActivityRepository activities, FollowUpRepository followUps,
                       AppUserRepository users, ReferralAssociateRepository associates,
                       StudentService studentService, CommissionService commissions, AuditService audit,
                       BranchRepository branches, CampaignRepository campaigns, StudentRepository students,
                       AlumniService alumni) {
        this.alumni = alumni;
        this.branches = branches;
        this.campaigns = campaigns;
        this.students = students;
        this.leads = leads;
        this.activities = activities;
        this.followUps = followUps;
        this.users = users;
        this.associates = associates;
        this.studentService = studentService;
        this.commissions = commissions;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public PageResponse<LeadListItem> search(String q, LeadStatus status, LeadSource source, Long counsellorId,
                                             boolean unassignedOnly, Long branchId, Long campaignId, int page,
                                             int size) {
        CurrentUser me = requireLeadAccess();
        Long branch = me.branchScope() != null ? me.branchScope() : branchId;
        Specification<Lead> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (branch != null) {
                p.add(cb.equal(root.get("branch").get("id"), branch));
            }
            if (campaignId != null) {
                p.add(cb.equal(root.get("campaign").get("id"), campaignId));
            }
            Join<Lead, AppUser> c = root.join("assignedCounsellor", JoinType.LEFT);
            if (!me.isAdmin()) {
                p.add(cb.or(cb.equal(c.get("id"), me.id()), cb.isNull(c.get("id"))));
            }
            if (unassignedOnly) {
                p.add(cb.isNull(c.get("id")));
            } else if (counsellorId != null) {
                p.add(cb.equal(c.get("id"), counsellorId));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                String digits = Phones.normalize(q.trim());
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("fullName")), like));
                any.add(cb.like(cb.lower(root.get("neetRollNo")), like));
                if (!digits.isEmpty()) {
                    any.add(cb.like(root.get("phone"), "%" + digits + "%"));
                }
                p.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (status != null) {
                p.add(cb.equal(root.get("status"), status));
            }
            if (source != null) {
                p.add(cb.equal(root.get("source"), source));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<Lead> result = leads.findAll(spec,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result, LeadListItem::of);
    }

    @Transactional(readOnly = true)
    public LeadResponse get(Long id) {
        return LeadResponse.of(loadVisible(id, requireLeadAccess()));
    }

    @Transactional(readOnly = true)
    public List<DuplicateRef> findDuplicates(String phone, String neetRollNo) {
        requireLeadAccess();
        return duplicatesOf(Phones.normalize(phone), normalizeRoll(neetRollNo), null).stream()
                .map(DuplicateRef::of).toList();
    }

    // ---------------------------------------------------------------- create / update

    @Transactional
    public LeadResponse create(LeadRequest req) {
        CurrentUser me = requireLeadAccess();
        Lead lead = new Lead();
        lead.setCreatedBy(me.id());
        applyFields(lead, req, me);
        checkDuplicates(lead, req.allowDuplicatePhone());
        if (lead.getAssignedCounsellor() == null && me.role() != Role.SUPER_ADMIN) {
            lead.setAssignedCounsellor(users.getReferenceById(me.id()));
        }
        leads.save(lead);
        audit.record(me.id(), "LEAD_CREATED", "LEAD", lead.getId(), "source=" + lead.getSource());
        return LeadResponse.of(lead);
    }

    /** Creates a lead from import without per-row auth checks; the caller has already authorised. */
    @Transactional
    public boolean importOne(Lead lead, CurrentUser me) {
        if (!duplicatesOf(lead.getPhone(), lead.getNeetRollNo(), null).isEmpty()) {
            return false;
        }
        lead.setCreatedBy(me.id());
        if (me.branchId() != null) {
            lead.setBranch(branches.getReferenceById(me.branchId()));
        }
        if (lead.getAssignedCounsellor() == null && me.role() != Role.SUPER_ADMIN) {
            lead.setAssignedCounsellor(users.getReferenceById(me.id()));
        }
        leads.save(lead);
        return true;
    }

    @Transactional
    public LeadResponse update(Long id, LeadRequest req) {
        CurrentUser me = requireLeadAccess();
        Lead lead = loadVisible(id, me);
        applyFields(lead, req, me);
        checkDuplicates(lead, req.allowDuplicatePhone());
        audit.record(me.id(), "LEAD_UPDATED", "LEAD", lead.getId(), null);
        return LeadResponse.of(lead);
    }

    @Transactional
    public LeadResponse changeStatus(Long id, LeadStatus status, String note) {
        CurrentUser me = requireLeadAccess();
        Lead lead = loadVisible(id, me);
        if (lead.getStatus() == status) {
            return LeadResponse.of(lead);
        }
        if (NEEDS_STUDENT.contains(status) && lead.getStudent() == null) {
            throw ApiException.badRequest("Convert this lead to a student before moving it to " + status);
        }
        LeadStatus from = lead.getStatus();
        lead.setStatus(status);
        activities.save(new LeadActivity(lead.getId(), ActivityType.STATUS_CHANGE, from + " -> " + status,
                blankToNull(note), users.getReferenceById(me.id())));
        audit.record(me.id(), "LEAD_STATUS_CHANGED", "LEAD", lead.getId(), from + " -> " + status);
        if (status == LeadStatus.ADMISSION_CONFIRMED) {
            commissions.onAdmissionConfirmed(lead, me.id());
            alumni.onAdmissionConfirmed(lead.getStudent(), me.id());
        }
        return LeadResponse.of(lead);
    }

    @Transactional
    public LeadResponse assign(Long id, Long userId) {
        CurrentUser me = requireLeadAccess();
        Lead lead = loadVisible(id, me);
        if (!me.isAdmin()) {
            boolean claimingUnassigned = lead.getAssignedCounsellor() == null && me.id().equals(userId);
            if (!claimingUnassigned) {
                throw ApiException.forbidden("Only an admin can reassign leads; you can only claim unassigned ones");
            }
        }
        AppUser assignee = null;
        if (userId != null) {
            assignee = users.findById(userId).filter(AppUser::isActive)
                    .filter(u -> LEAD_ROLES.contains(u.getRole()))
                    .orElseThrow(() -> ApiException.badRequest("That user cannot be assigned leads"));
        }
        lead.setAssignedCounsellor(assignee);
        String label = assignee == null ? "Unassigned" : "Assigned to " + assignee.getFullName();
        activities.save(new LeadActivity(lead.getId(), ActivityType.ASSIGNMENT, label, null,
                users.getReferenceById(me.id())));
        audit.record(me.id(), "LEAD_ASSIGNED", "LEAD", lead.getId(), label);
        return LeadResponse.of(lead);
    }

    /** Creates the student profile for this lead and links the two (spec 4.1 -> 4.2). */
    @Transactional
    public LeadResponse convertToStudent(Long id, StudentRequest req) {
        CurrentUser me = requireLeadAccess();
        Lead lead = loadVisible(id, me);
        if (lead.getStudent() != null) {
            throw ApiException.conflict("This lead is already linked to a student")
                    .with("studentId", lead.getStudent().getId());
        }
        StudentRequest withOwner = req.assignedCounsellorId() != null || lead.getAssignedCounsellor() == null
                ? req : withCounsellor(req, lead.getAssignedCounsellor().getId(), me);
        Student student = studentService.createEntity(withOwner);
        if (lead.getBranch() != null) {
            student.setBranch(lead.getBranch());
        }
        lead.setStudent(student);
        if (lead.getStatus() == LeadStatus.NEW) {
            lead.setStatus(LeadStatus.QUALIFIED);
        }
        activities.save(new LeadActivity(lead.getId(), ActivityType.NOTE, "Converted to student",
                "Student #" + student.getId(), users.getReferenceById(me.id())));
        audit.record(me.id(), "LEAD_CONVERTED", "LEAD", lead.getId(), "studentId=" + student.getId());
        return LeadResponse.of(lead);
    }

    // ---------------------------------------------------------------- activities & follow-ups

    @Transactional(readOnly = true)
    public List<ActivityResponse> activities(Long leadId) {
        loadVisible(leadId, requireLeadAccess());
        return activities.findByLeadIdOrderByCreatedAtDesc(leadId).stream().map(ActivityResponse::of).toList();
    }

    @Transactional
    public ActivityResponse addActivity(Long leadId, ActivityRequest req) {
        CurrentUser me = requireLeadAccess();
        loadVisible(leadId, me);
        if (req.type() == ActivityType.STATUS_CHANGE || req.type() == ActivityType.ASSIGNMENT) {
            throw ApiException.badRequest("That activity type is recorded automatically");
        }
        LeadActivity a = activities.save(new LeadActivity(leadId, req.type(), blankToNull(req.outcome()),
                blankToNull(req.notes()), users.getReferenceById(me.id())));
        return ActivityResponse.of(a);
    }

    @Transactional(readOnly = true)
    public List<FollowUpResponse> followUpsForLead(Long leadId) {
        loadVisible(leadId, requireLeadAccess());
        return followUps.findByLeadIdOrderByDueAtAsc(leadId).stream().map(FollowUpResponse::of).toList();
    }

    @Transactional
    public FollowUpResponse addFollowUp(Long leadId, FollowUpRequest req) {
        CurrentUser me = requireLeadAccess();
        Lead lead = loadVisible(leadId, me);
        Long assigneeId = req.assignedToId() != null ? req.assignedToId() : me.id();
        if (!assigneeId.equals(me.id()) && !me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can schedule follow-ups for someone else");
        }
        AppUser assignee = users.findById(assigneeId).filter(AppUser::isActive)
                .filter(u -> LEAD_ROLES.contains(u.getRole()))
                .orElseThrow(() -> ApiException.badRequest("That user cannot own follow-ups"));
        FollowUp f = followUps.save(new FollowUp(lead, assignee, req.dueAt(), req.purpose().trim(), me.id()));
        return FollowUpResponse.of(f);
    }

    @Transactional
    public FollowUpResponse completeFollowUp(Long followUpId) {
        CurrentUser me = requireLeadAccess();
        FollowUp f = followUps.findById(followUpId).orElseThrow(() -> ApiException.notFound("Follow-up"));
        if (!me.isAdmin() && !f.getAssignedTo().getId().equals(me.id())) {
            throw ApiException.notFound("Follow-up");
        }
        if (f.getCompletedAt() == null) {
            f.complete(me.id());
        }
        return FollowUpResponse.of(f);
    }

    /** The current user's open follow-ups due within the next {@code days} days, overdue ones included. */
    @Transactional(readOnly = true)
    public List<FollowUpResponse> myFollowUps(int days) {
        CurrentUser me = requireLeadAccess();
        Instant before = Instant.now().plusSeconds(86_400L * Math.clamp(days, 0, 60));
        return followUps.findOpenForUserDueBefore(me.id(), before).stream().map(FollowUpResponse::of).toList();
    }

    // ---------------------------------------------------------------- helpers

    private void applyFields(Lead lead, LeadRequest req, CurrentUser me) {
        lead.setFullName(req.fullName().trim());
        lead.setPhone(Phones.normalize(req.phone()));
        lead.setAltPhone(Phones.normalize(blankToNull(req.altPhone())));
        lead.setEmail(blankToNull(req.email()));
        lead.setNeetRollNo(normalizeRoll(req.neetRollNo()));
        lead.setNeetScore(req.neetScore());
        lead.setNeetAir(req.neetAir());
        lead.setCategory(req.category());
        lead.setHomeState(blankToNull(req.homeState()));
        lead.setDomicileStatus(req.domicileStatus());
        lead.setSource(req.source());
        lead.setLanguagePreference(req.languagePreference() == null ? Language.ENGLISH : req.languagePreference());
        lead.setNotes(blankToNull(req.notes()));

        if (req.referralAssociateId() == null) {
            lead.setReferralAssociate(null);
        } else {
            lead.setReferralAssociate(associates.findById(req.referralAssociateId())
                    .orElseThrow(() -> ApiException.badRequest("Referral associate not found")));
        }
        if (req.source() == LeadSource.REFERRAL_ASSOCIATE && lead.getReferralAssociate() == null) {
            throw ApiException.badRequest("Pick the referral associate for a referral lead");
        }

        // Branch staff always work inside their own branch; head office may place a lead anywhere.
        Long scope = me.branchScope();
        if (scope != null) {
            if (lead.getId() == null) {
                lead.setBranch(branches.getReferenceById(scope));
            }
        } else {
            lead.setBranch(req.branchId() == null ? null : branches.findById(req.branchId())
                    .orElseThrow(() -> ApiException.badRequest("Branch not found")));
        }
        lead.setCampaign(req.campaignId() == null ? null : campaigns.findById(req.campaignId())
                .orElseThrow(() -> ApiException.badRequest("Campaign not found")));
        if (req.referredByStudentId() == null) {
            lead.setReferredByStudent(null);
        } else {
            Student referrer = students.findById(req.referredByStudentId())
                    .filter(s -> !me.outsideBranch(s.getBranch()))
                    .orElseThrow(() -> ApiException.badRequest("Referring student not found"));
            lead.setReferredByStudent(referrer);
        }

        Long current = lead.getAssignedCounsellor() == null ? null : lead.getAssignedCounsellor().getId();
        Long wanted = req.assignedCounsellorId();
        boolean changing = lead.getId() == null ? wanted != null : !java.util.Objects.equals(current, wanted);
        if (changing) {
            if (!me.isAdmin()) {
                throw ApiException.forbidden("Only an admin can change who a lead is assigned to");
            }
            lead.setAssignedCounsellor(wanted == null ? null : users.findById(wanted).filter(AppUser::isActive)
                    .filter(u -> LEAD_ROLES.contains(u.getRole()))
                    .orElseThrow(() -> ApiException.badRequest("That user cannot be assigned leads")));
        }
    }

    private void checkDuplicates(Lead lead, boolean allowDuplicatePhone) {
        List<Lead> byRoll = lead.getNeetRollNo() == null ? List.of()
                : leads.findByNeetRollNo(lead.getNeetRollNo()).stream().filter(l -> !l.getId().equals(lead.getId()))
                        .toList();
        if (!byRoll.isEmpty()) {
            throw ApiException.conflict("A lead with this NEET roll number already exists")
                    .with("duplicates", byRoll.stream().map(DuplicateRef::of).toList());
        }
        if (allowDuplicatePhone) {
            return;
        }
        List<Lead> dupes = duplicatesOf(lead.getPhone(), null, lead.getId());
        if (lead.getAltPhone() != null) {
            dupes = new ArrayList<>(dupes);
            for (Lead l : duplicatesOf(lead.getAltPhone(), null, lead.getId())) {
                if (dupes.stream().noneMatch(d -> d.getId().equals(l.getId()))) {
                    dupes.add(l);
                }
            }
        }
        if (!dupes.isEmpty()) {
            throw ApiException.conflict("A lead with this phone number already exists")
                    .with("duplicates", dupes.stream().map(DuplicateRef::of).toList())
                    .with("canOverride", true);
        }
    }

    private List<Lead> duplicatesOf(String phone, String roll, Long excludeId) {
        Map<Long, Lead> found = new LinkedHashMap<>();
        if (phone != null && !phone.isBlank()) {
            leads.findByAnyPhone(phone).forEach(l -> found.put(l.getId(), l));
        }
        if (roll != null) {
            leads.findByNeetRollNo(roll).forEach(l -> found.put(l.getId(), l));
        }
        if (excludeId != null) {
            found.remove(excludeId);
        }
        return new ArrayList<>(found.values());
    }

    private Lead loadVisible(Long id, CurrentUser me) {
        Lead lead = leads.findById(id).orElseThrow(() -> ApiException.notFound("Lead"));
        if (me.outsideBranch(lead.getBranch())) {
            throw ApiException.notFound("Lead");
        }
        if (!me.isAdmin() && lead.getAssignedCounsellor() != null
                && !lead.getAssignedCounsellor().getId().equals(me.id())) {
            throw ApiException.notFound("Lead");
        }
        return lead;
    }

    static CurrentUser requireLeadAccess() {
        CurrentUser me = CurrentUser.get();
        if (!LEAD_ROLES.contains(me.role())) {
            throw ApiException.forbidden("You do not have access to leads");
        }
        return me;
    }

    private static StudentRequest withCounsellor(StudentRequest r, Long counsellorId, CurrentUser me) {
        // Only admins may set a counsellor on the student; otherwise StudentService assigns the creator.
        if (!me.isAdmin()) {
            return r;
        }
        return new StudentRequest(r.fullName(), r.dateOfBirth(), r.gender(), r.phone(), r.email(), r.parentName(),
                r.parentPhone(), r.category(), r.pwd(), r.homeState(), r.domicileStatus(), r.nationality(),
                r.nriSponsored(), r.neetYear(), r.neetRollNo(), r.neetQualified(), r.neetScore(),
                r.neetPercentile(), r.neetAir(), r.categoryRank(), r.categoryCertValidUntil(),
                r.languagePreference(), r.apaarId(), counsellorId, r.branchId());
    }

    static String normalizeRoll(String roll) {
        return roll == null || roll.isBlank() ? null : roll.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
