package com.mbbscrm.crm.alumni;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.branch.BranchRef;
import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.college.CollegeRepository;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.common.Quota;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.counselling.AllotmentResult;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Alumni directory, satisfaction surveys, testimonials and referral tracking (spec 4.23). Admins and
 * counsellors can use the directory (within their branch); only an admin can approve a testimonial for
 * public use.
 */
@Service
public class AlumniService {

    static final Set<Role> ROLES = EnumSet.of(Role.SUPER_ADMIN, Role.COUNSELLOR);

    private final AlumniRepository alumni;
    private final AlumniSurveyRepository surveys;
    private final TestimonialRepository testimonials;
    private final StudentService students;
    private final CollegeRepository colleges;
    private final AppUserRepository users;
    private final EntityManager em;
    private final AuditService audit;

    public AlumniService(AlumniRepository alumni, AlumniSurveyRepository surveys, TestimonialRepository testimonials,
                         StudentService students, CollegeRepository colleges, AppUserRepository users,
                         EntityManager em, AuditService audit) {
        this.alumni = alumni;
        this.surveys = surveys;
        this.testimonials = testimonials;
        this.students = students;
        this.colleges = colleges;
        this.users = users;
        this.em = em;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ DTOs

    public record AlumniRequest(
            Long collegeId,
            @Size(max = 200) String collegeName,
            @NotNull Course course,
            Quota quota,
            @NotNull @Min(2013) @Max(2100) Integer admissionYear,
            boolean willingToRefer,
            @Size(max = 1000) String notes) {
    }

    public record AlumniRow(Long id, Long studentId, String studentName, String phone, Long collegeId,
                            String collegeName, Course course, Quota quota, int admissionYear, boolean willingToRefer,
                            UserRef counsellor, BranchRef branch, Integer latestRating, Boolean wouldRecommend,
                            long referrals, long referralAdmissions) {
    }

    public record SurveyRequest(@NotNull @Min(1) @Max(5) Integer overallRating, @Min(1) @Max(5) Integer counsellorRating,
                                boolean wouldRecommend, @Size(max = 2000) String comments) {
    }

    public record SurveyView(Long id, int overallRating, Integer counsellorRating, boolean wouldRecommend,
                             String comments, UserRef recordedBy, Instant recordedAt) {
        static SurveyView of(AlumniSurvey s) {
            return new SurveyView(s.getId(), s.getOverallRating(), s.getCounsellorRating(), s.isWouldRecommend(),
                    s.getComments(), UserRef.of(s.getRecordedBy()), s.getRecordedAt());
        }
    }

    public record TestimonialRequest(@NotBlank @Size(max = 2000) String quote, boolean consent) {
    }

    public record TestimonialView(Long id, String quote, boolean consent, Testimonial.Status status,
                                  UserRef recordedBy, UserRef reviewedBy, Instant createdAt) {
        static TestimonialView of(Testimonial t) {
            return new TestimonialView(t.getId(), t.getQuote(), t.isConsent(), t.getStatus(),
                    UserRef.of(t.getRecordedBy()), UserRef.of(t.getReviewedBy()), t.getCreatedAt());
        }
    }

    public record ReferralRow(Long leadId, String leadName, LeadStatus status, Instant createdAt) {
    }

    public record AlumniDetail(AlumniRow alumni, String notes, List<SurveyView> surveys,
                               List<TestimonialView> testimonials, List<ReferralRow> referrals) {
    }

    public record Summary(long total, long willingToRefer, long surveyed, Double averageRating,
                          Integer recommendPercent, long referrals, long referralAdmissions,
                          long testimonialsPending) {
    }

    public record Directory(Summary summary, List<AlumniRow> rows) {
    }

    // ------------------------------------------------------------------ directory

    @Transactional(readOnly = true)
    public Directory directory(String q) {
        CurrentUser me = requireRole();
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        Map<Long, AlumniSurvey> latest = new HashMap<>();
        for (AlumniSurvey s : surveys.findAllByOrderByRecordedAtDesc()) {
            latest.putIfAbsent(s.getAlumni().getId(), s);
        }
        Map<Long, long[]> referrals = referralCounts();

        List<AlumniRow> all = alumni.findAllByOrderByAdmissionYearDescCreatedAtDesc().stream()
                .filter(a -> !me.outsideBranch(a.getStudent().getBranch()))
                .map(a -> row(a, latest.get(a.getId()), referrals.get(a.getStudent().getId()))).toList();

        long surveyed = all.stream().filter(r -> r.latestRating() != null).count();
        Double average = surveyed == 0 ? null
                : Math.round(all.stream().filter(r -> r.latestRating() != null).mapToInt(AlumniRow::latestRating)
                        .average().orElse(0) * 10) / 10.0;
        Integer recommend = surveyed == 0 ? null
                : (int) Math.round(100.0 * all.stream().filter(r -> Boolean.TRUE.equals(r.wouldRecommend())).count()
                        / surveyed);
        Summary summary = new Summary(all.size(), all.stream().filter(AlumniRow::willingToRefer).count(), surveyed,
                average, recommend, all.stream().mapToLong(AlumniRow::referrals).sum(),
                all.stream().mapToLong(AlumniRow::referralAdmissions).sum(),
                me.isAdmin() ? testimonials.countByStatus(Testimonial.Status.PENDING) : 0);

        List<AlumniRow> rows = needle == null ? all : all.stream()
                .filter(r -> r.studentName().toLowerCase(Locale.ROOT).contains(needle)
                        || r.collegeName().toLowerCase(Locale.ROOT).contains(needle)
                        || r.phone().contains(needle))
                .toList();
        return new Directory(summary, rows);
    }

    @Transactional(readOnly = true)
    public AlumniDetail get(Long id) {
        return detail(load(id));
    }

    /** The alumni record for a student, or null if they are not (yet) marked as admitted. */
    @Transactional(readOnly = true)
    public AlumniDetail forStudent(Long studentId) {
        requireRole();
        students.requireAccess(studentId, EnumSet.of(Role.SUPER_ADMIN), true);
        return alumni.findByStudentId(studentId).map(this::detail).orElse(null);
    }

    @Transactional
    public AlumniDetail create(Long studentId, AlumniRequest req) {
        CurrentUser me = requireRole();
        Student s = students.requireAccess(studentId, EnumSet.of(Role.SUPER_ADMIN), true);
        if (alumni.existsByStudentId(s.getId())) {
            throw ApiException.conflict("This student is already in the alumni directory");
        }
        Alumni a = new Alumni(s, me.id());
        apply(a, req);
        alumni.save(a);
        audit.record(me.id(), "ALUMNI_CREATED", "ALUMNI", a.getId(), a.getCollegeName() + " " + a.getAdmissionYear());
        return detail(a);
    }

    @Transactional
    public AlumniDetail update(Long id, AlumniRequest req) {
        Alumni a = load(id);
        apply(a, req);
        audit.record(CurrentUser.get().id(), "ALUMNI_UPDATED", "ALUMNI", a.getId(), null);
        return detail(a);
    }

    /**
     * Called inside the lead status change when an admission is confirmed: adds the student to the directory
     * using the seat they froze, if we have one on record. Otherwise staff add them by hand.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAdmissionConfirmed(Student s, Long actorId) {
        if (s == null || alumni.existsByStudentId(s.getId())) {
            return;
        }
        List<AllotmentResult> frozen = em.createQuery("""
                select a from AllotmentResult a join fetch a.college join fetch a.round
                where a.studentCounselling.student.id = :studentId
                  and a.decision = com.mbbscrm.crm.counselling.Decision.FREEZE
                order by a.decidedAt desc""", AllotmentResult.class)
                .setParameter("studentId", s.getId()).setMaxResults(1).getResultList();
        if (frozen.isEmpty()) {
            return;
        }
        AllotmentResult seat = frozen.get(0);
        Alumni a = new Alumni(s, actorId);
        a.setCollege(seat.getCollege());
        a.setCollegeName(seat.getCollege().getName());
        a.setCourse(seat.getCourse() == null ? Course.MBBS : seat.getCourse());
        a.setQuota(seat.getQuota());
        a.setAdmissionYear(seat.getRound().getAcademicYear());
        alumni.save(a);
        audit.record(actorId, "ALUMNI_CREATED", "ALUMNI", a.getId(), "auto: " + a.getCollegeName());
    }

    // ------------------------------------------------------------------ surveys & testimonials

    @Transactional
    public AlumniDetail addSurvey(Long id, SurveyRequest req) {
        Alumni a = load(id);
        CurrentUser me = CurrentUser.get();
        surveys.save(new AlumniSurvey(a, req.overallRating(), req.counsellorRating(), req.wouldRecommend(),
                blankToNull(req.comments()), users.getReferenceById(me.id())));
        audit.record(me.id(), "ALUMNI_SURVEY_RECORDED", "ALUMNI", a.getId(), "rating=" + req.overallRating());
        return detail(a);
    }

    @Transactional
    public AlumniDetail addTestimonial(Long id, TestimonialRequest req) {
        Alumni a = load(id);
        CurrentUser me = CurrentUser.get();
        testimonials.save(new Testimonial(a, req.quote().trim(), req.consent(), users.getReferenceById(me.id())));
        audit.record(me.id(), "TESTIMONIAL_RECORDED", "ALUMNI", a.getId(), "consent=" + req.consent());
        return detail(a);
    }

    @Transactional
    public AlumniDetail reviewTestimonial(Long testimonialId, boolean approve) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can approve testimonials");
        }
        Testimonial t = testimonials.findById(testimonialId).orElseThrow(() -> ApiException.notFound("Testimonial"));
        if (approve && !t.isConsent()) {
            throw ApiException.badRequest("This testimonial has no consent to publish, so it cannot be approved");
        }
        t.review(approve ? Testimonial.Status.APPROVED : Testimonial.Status.REJECTED, users.getReferenceById(me.id()));
        audit.record(me.id(), approve ? "TESTIMONIAL_APPROVED" : "TESTIMONIAL_REJECTED", "ALUMNI",
                t.getAlumni().getId(), null);
        return detail(t.getAlumni());
    }

    // ------------------------------------------------------------------ helpers

    private void apply(Alumni a, AlumniRequest req) {
        College college = req.collegeId() == null ? null : colleges.findById(req.collegeId())
                .orElseThrow(() -> ApiException.badRequest("College not found"));
        String name = college != null ? college.getName() : blankToNull(req.collegeName());
        if (name == null) {
            throw ApiException.badRequest("Pick the college or type its name");
        }
        if (req.admissionYear() > LocalDate.now().getYear() + 1) {
            throw ApiException.badRequest("The admission year is too far in the future");
        }
        a.setCollege(college);
        a.setCollegeName(name);
        a.setCourse(req.course());
        a.setQuota(req.quota());
        a.setAdmissionYear(req.admissionYear());
        a.setWillingToRefer(req.willingToRefer());
        a.setNotes(blankToNull(req.notes()));
    }

    private AlumniDetail detail(Alumni a) {
        List<AlumniSurvey> s = surveys.findByAlumniIdOrderByRecordedAtDesc(a.getId());
        List<ReferralRow> referrals = em.createQuery("""
                select l.id, l.fullName, l.status, l.createdAt from Lead l
                where l.referredByStudent.id = :studentId order by l.createdAt desc""", Object[].class)
                .setParameter("studentId", a.getStudent().getId()).getResultList().stream()
                .map(r -> new ReferralRow((Long) r[0], (String) r[1], (LeadStatus) r[2], (Instant) r[3])).toList();
        long admitted = referrals.stream().filter(r -> r.status() == LeadStatus.ADMISSION_CONFIRMED).count();
        return new AlumniDetail(row(a, s.isEmpty() ? null : s.get(0), new long[] {referrals.size(), admitted}),
                a.getNotes(), s.stream().map(SurveyView::of).toList(),
                testimonials.findByAlumniIdOrderByCreatedAtDesc(a.getId()).stream().map(TestimonialView::of).toList(),
                referrals);
    }

    private static AlumniRow row(Alumni a, AlumniSurvey latest, long[] referrals) {
        Student s = a.getStudent();
        return new AlumniRow(a.getId(), s.getId(), s.getFullName(), s.getPhone(),
                a.getCollege() == null ? null : a.getCollege().getId(), a.getCollegeName(), a.getCourse(), a.getQuota(),
                a.getAdmissionYear(), a.isWillingToRefer(), UserRef.of(s.getAssignedCounsellor()),
                BranchRef.of(s.getBranch()), latest == null ? null : latest.getOverallRating(),
                latest == null ? null : latest.isWouldRecommend(), referrals == null ? 0 : referrals[0],
                referrals == null ? 0 : referrals[1]);
    }

    /** student id -> [leads referred, of which admitted]. */
    private Map<Long, long[]> referralCounts() {
        Map<Long, long[]> out = new HashMap<>();
        for (Object[] r : em.createQuery("""
                select l.referredByStudent.id, count(l),
                       sum(case when l.status = com.mbbscrm.crm.common.LeadStatus.ADMISSION_CONFIRMED then 1 else 0 end)
                from Lead l where l.referredByStudent is not null group by l.referredByStudent.id""", Object[].class)
                .getResultList()) {
            out.put((Long) r[0], new long[] {((Number) r[1]).longValue(), ((Number) r[2]).longValue()});
        }
        return out;
    }

    private Alumni load(Long id) {
        CurrentUser me = requireRole();
        Alumni a = alumni.findById(id).orElseThrow(() -> ApiException.notFound("Alumni record"));
        if (me.outsideBranch(a.getStudent().getBranch())) {
            throw ApiException.notFound("Alumni record");
        }
        return a;
    }

    private static CurrentUser requireRole() {
        CurrentUser me = CurrentUser.get();
        if (!ROLES.contains(me.role())) {
            throw ApiException.forbidden("You do not have access to the alumni directory");
        }
        return me;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
