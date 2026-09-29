package com.mbbscrm.crm.student;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.PageResponse;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.StudentDtos.StudentListItem;
import com.mbbscrm.crm.student.StudentDtos.StudentRequest;
import com.mbbscrm.crm.student.StudentDtos.StudentResponse;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

import jakarta.persistence.criteria.Predicate;

@Service
public class StudentService {

    /** Roles that may view student profiles. Counsellors only see students assigned to them. */
    public static final EnumSet<Role> READ_ROLES = EnumSet.of(Role.SUPER_ADMIN, Role.COUNSELLOR,
            Role.DOCUMENTATION_EXEC, Role.ACCOUNTANT, Role.LOAN_DESK, Role.GRIEVANCE_OFFICER);

    private final StudentRepository students;
    private final LeadRepository leads;
    private final AppUserRepository users;
    private final EligibilityService eligibility;
    private final AuditService audit;

    public StudentService(StudentRepository students, LeadRepository leads, AppUserRepository users,
                          EligibilityService eligibility, AuditService audit) {
        this.students = students;
        this.leads = leads;
        this.users = users;
        this.eligibility = eligibility;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<StudentListItem> search(String q, Category category, String homeState, Long counsellorId,
                                                int page, int size) {
        CurrentUser me = requireRead();
        Specification<Student> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (me.role() == Role.COUNSELLOR) {
                p.add(cb.equal(root.get("assignedCounsellor").get("id"), me.id()));
            } else if (counsellorId != null) {
                p.add(cb.equal(root.get("assignedCounsellor").get("id"), counsellorId));
            }
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("fullName")), like),
                        cb.like(root.get("phone"), "%" + Phones.normalize(q.trim()) + "%"),
                        cb.like(cb.lower(root.get("neetRollNo")), like)));
            }
            if (category != null) {
                p.add(cb.equal(root.get("category"), category));
            }
            if (homeState != null && !homeState.isBlank()) {
                p.add(cb.equal(root.get("homeState"), homeState));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<Student> result = students.findAll(spec,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result, StudentListItem::of);
    }

    @Transactional(readOnly = true)
    public StudentResponse get(Long id) {
        Student s = loadReadable(id);
        return toResponse(s);
    }

    @Transactional
    public StudentResponse create(StudentRequest req) {
        return toResponse(createEntity(req));
    }

    @Transactional
    public StudentResponse update(Long id, StudentRequest req) {
        CurrentUser me = requireWrite();
        Student s = loadReadable(id);
        apply(s, req, me);
        audit.record(me.id(), "STUDENT_UPDATED", "STUDENT", s.getId(), null);
        return toResponse(s);
    }

    /** Also used by lead conversion. Counsellors own the students they create. */
    @Transactional
    public Student createEntity(StudentRequest req) {
        CurrentUser me = requireWrite();
        Student s = new Student();
        apply(s, req, me);
        if (s.getAssignedCounsellor() == null && me.role() == Role.COUNSELLOR) {
            s.setAssignedCounsellor(users.getReferenceById(me.id()));
        }
        students.save(s);
        audit.record(me.id(), "STUDENT_CREATED", "STUDENT", s.getId(), null);
        return s;
    }

    private void apply(Student s, StudentRequest req, CurrentUser me) {
        String roll = blankToNull(req.neetRollNo());
        if (roll != null) {
            roll = roll.toUpperCase(Locale.ROOT);
            String finalRoll = roll;
            students.findByNeetRollNo(roll).filter(other -> !other.getId().equals(s.getId())).ifPresent(other -> {
                throw ApiException.conflict("Another student already has NEET roll number " + finalRoll)
                        .with("existingStudentId", other.getId());
            });
        }
        s.setFullName(req.fullName().trim());
        s.setDateOfBirth(req.dateOfBirth());
        s.setGender(req.gender());
        s.setPhone(Phones.normalize(req.phone()));
        s.setEmail(blankToNull(req.email()));
        s.setParentName(blankToNull(req.parentName()));
        s.setParentPhone(Phones.normalize(blankToNull(req.parentPhone())));
        s.setCategory(req.category());
        s.setPwd(req.pwd());
        s.setHomeState(req.homeState().trim());
        s.setDomicileStatus(req.domicileStatus());
        s.setNationality(req.nationality());
        s.setNriSponsored(req.nriSponsored());
        s.setNeetYear(req.neetYear());
        s.setNeetRollNo(roll);
        s.setNeetQualified(req.neetQualified());
        s.setNeetScore(req.neetScore());
        s.setNeetPercentile(req.neetPercentile());
        s.setNeetAir(req.neetAir());
        s.setCategoryRank(req.categoryRank());
        s.setCategoryCertValidUntil(req.categoryCertValidUntil());
        s.setLanguagePreference(req.languagePreference());
        s.setApaarId(blankToNull(req.apaarId()));

        Long current = s.getAssignedCounsellor() == null ? null : s.getAssignedCounsellor().getId();
        Long wanted = req.assignedCounsellorId();
        if (wanted != null && !wanted.equals(current)) {
            if (!me.isAdmin()) {
                throw ApiException.forbidden("Only an admin can reassign a student");
            }
            AppUser counsellor = users.findById(wanted).filter(AppUser::isActive)
                    .orElseThrow(() -> ApiException.badRequest("Assigned counsellor not found or inactive"));
            s.setAssignedCounsellor(counsellor);
        } else if (wanted == null && current != null && me.isAdmin()) {
            s.setAssignedCounsellor(null);
        }
    }

    private StudentResponse toResponse(Student s) {
        Long leadId = s.getId() == null ? null : leads.findFirstByStudentId(s.getId()).map(l -> l.getId()).orElse(null);
        return StudentResponse.of(s, leadId, eligibility.evaluate(s));
    }

    /**
     * Module-specific access: roles in {@code fullAccess} may act on any student; counsellors (if allowed)
     * only on their own. Everyone else is refused.
     */
    @Transactional(readOnly = true)
    public Student requireAccess(Long id, java.util.Set<Role> fullAccess, boolean counsellorOwn) {
        CurrentUser me = CurrentUser.get();
        Student s = students.findById(id).orElseThrow(() -> ApiException.notFound("Student"));
        if (fullAccess.contains(me.role())) {
            return s;
        }
        if (counsellorOwn && me.role() == Role.COUNSELLOR) {
            if (s.getAssignedCounsellor() != null && s.getAssignedCounsellor().getId().equals(me.id())) {
                return s;
            }
            throw ApiException.notFound("Student");
        }
        throw ApiException.forbidden("You do not have access to this");
    }

    /** Loads a student the current user may view; others get 404 so their existence is not revealed. */
    @Transactional(readOnly = true)
    public Student requireReadable(Long id) {
        return loadReadable(id);
    }

    /** Loads a student the current user may act on (admins, or the assigned counsellor). */
    @Transactional(readOnly = true)
    public Student requireWritable(Long id) {
        requireWrite();
        return loadReadable(id);
    }

    private Student loadReadable(Long id) {
        CurrentUser me = requireRead();
        Student s = students.findById(id).orElseThrow(() -> ApiException.notFound("Student"));
        if (me.role() == Role.COUNSELLOR
                && (s.getAssignedCounsellor() == null || !s.getAssignedCounsellor().getId().equals(me.id()))) {
            throw ApiException.notFound("Student");
        }
        return s;
    }

    private static CurrentUser requireRead() {
        CurrentUser me = CurrentUser.get();
        if (!READ_ROLES.contains(me.role())) {
            throw ApiException.forbidden("You do not have access to student profiles");
        }
        return me;
    }

    private static CurrentUser requireWrite() {
        CurrentUser me = CurrentUser.get();
        if (me.role() != Role.SUPER_ADMIN && me.role() != Role.COUNSELLOR) {
            throw ApiException.forbidden("Only admins and counsellors can edit student profiles");
        }
        return me;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
