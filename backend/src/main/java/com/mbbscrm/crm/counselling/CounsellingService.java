package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.OutboundMessageRepository;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.college.CollegeRepository;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.common.Quota;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.counselling.CounsellingDtos.AllotmentRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.AllotmentResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.AuthorityRef;
import com.mbbscrm.crm.counselling.CounsellingDtos.ChoiceItemRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.ChoiceItemResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.ChoiceListResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.ChoiceListSummary;
import com.mbbscrm.crm.counselling.CounsellingDtos.DecisionPreview;
import com.mbbscrm.crm.counselling.CounsellingDtos.DecisionRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.DeskRow;
import com.mbbscrm.crm.counselling.CounsellingDtos.LockRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.RoundDesk;
import com.mbbscrm.crm.counselling.CounsellingDtos.RoundResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.TrackRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.TrackResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.TrackUpdateRequest;
import com.mbbscrm.crm.refund.RefundRuleEngine;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import tools.jackson.databind.ObjectMapper;

/**
 * Counselling mechanics for one student (spec 4.5, 4.6): parallel tracks per authority, choice lists per
 * round, allotment results and the decision on each seat. Access follows the student: admins everywhere,
 * counsellors only for their own students.
 */
@Service
public class CounsellingService {

    private final StudentService studentService;
    private final AuthorityRepository authorities;
    private final RoundRepository rounds;
    private final StudentCounsellingRepository tracks;
    private final ChoiceListRepository choiceLists;
    private final AllotmentRepository allotments;
    private final CollegeRepository colleges;
    private final AppUserRepository users;
    private final OutboundMessageRepository messages;
    private final AlertService alerts;
    private final AlertTexts texts;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final RefundRuleEngine refundRules;

    public CounsellingService(StudentService studentService, AuthorityRepository authorities, RoundRepository rounds,
                              StudentCounsellingRepository tracks, ChoiceListRepository choiceLists,
                              AllotmentRepository allotments, CollegeRepository colleges, AppUserRepository users,
                              OutboundMessageRepository messages, AlertService alerts, AlertTexts texts,
                              AuditService audit, ObjectMapper mapper, RefundRuleEngine refundRules) {
        this.studentService = studentService;
        this.authorities = authorities;
        this.rounds = rounds;
        this.tracks = tracks;
        this.choiceLists = choiceLists;
        this.allotments = allotments;
        this.colleges = colleges;
        this.users = users;
        this.messages = messages;
        this.alerts = alerts;
        this.texts = texts;
        this.audit = audit;
        this.mapper = mapper;
        this.refundRules = refundRules;
    }

    // ================================================================== tracks

    @Transactional(readOnly = true)
    public List<TrackResponse> tracksFor(Long studentId) {
        studentService.requireReadable(studentId);
        return tracks.findByStudentIdOrderByAcademicYearDescAuthorityIdAsc(studentId).stream()
                .map(this::toTrackResponse).toList();
    }

    /** For callers that have already checked access themselves, such as the family portal. */
    @Transactional(readOnly = true)
    public List<TrackResponse> tracksForStudent(Student student) {
        return tracks.findByStudentIdOrderByAcademicYearDescAuthorityIdAsc(student.getId()).stream()
                .map(this::toTrackResponse).toList();
    }

    @Transactional
    public TrackResponse createTrack(Long studentId, TrackRequest req) {
        Student student = studentService.requireWritable(studentId);
        CounsellingAuthority authority = authorities.findById(req.authorityId())
                .orElseThrow(() -> ApiException.badRequest("Authority not found"));
        tracks.findByStudentIdAndAuthorityIdAndAcademicYear(studentId, authority.getId(), req.academicYear())
                .ifPresent(t -> {
                    throw ApiException.conflict("This student already has a " + authority.getCode() + " "
                            + req.academicYear() + " track");
                });
        StudentCounselling t = new StudentCounselling();
        t.setStudent(student);
        t.setAuthority(authority);
        t.setAcademicYear(req.academicYear());
        t.setRegistrationNo(blankToNull(req.registrationNo()));
        t.setStatus(req.status());
        tracks.save(t);
        audit.record(me().id(), "TRACK_CREATED", "STUDENT_COUNSELLING", t.getId(),
                "student=" + studentId + " " + authority.getCode() + " " + req.academicYear());
        return toTrackResponse(t);
    }

    @Transactional
    public TrackResponse updateTrack(Long trackId, TrackUpdateRequest req) {
        StudentCounselling t = writableTrack(trackId);
        CounsellingStatus before = t.getStatus();
        t.setRegistrationNo(blankToNull(req.registrationNo()));
        t.setStatus(req.status());
        audit.record(me().id(), "TRACK_UPDATED", "STUDENT_COUNSELLING", t.getId(), before + " -> " + t.getStatus());
        return toTrackResponse(t);
    }

    private TrackResponse toTrackResponse(StudentCounselling t) {
        List<RoundResponse> roundList = rounds
                .findByAuthorityIdAndAcademicYearOrderByRoundTypeAsc(t.getAuthority().getId(), t.getAcademicYear())
                .stream().map(RoundResponse::of).toList();
        List<ChoiceListSummary> lists = choiceLists.findByStudentCounsellingIdOrderByRoundRoundTypeAsc(t.getId())
                .stream().map(cl -> new ChoiceListSummary(cl.getId(), cl.getRound().getId(), cl.getRound().label(),
                        cl.getStatus(), cl.getItems().size(), cl.getLockedAt()))
                .toList();
        List<AllotmentResponse> allots = allotments.findByStudentCounsellingIdOrderByRoundRoundTypeAsc(t.getId())
                .stream().map(AllotmentResponse::of).toList();
        return new TrackResponse(t.getId(), t.getStudent().getId(), AuthorityRef.of(t.getAuthority()),
                t.getAcademicYear(), t.getRegistrationNo(), t.getStatus(), roundList, lists, allots);
    }

    // ================================================================== choice lists

    /** Returns the track's list for the round, creating an empty draft the first time. */
    @Transactional
    public ChoiceListResponse openChoiceList(Long trackId, Long roundId) {
        StudentCounselling t = writableTrack(trackId);
        CounsellingRoundEntity round = roundOfTrack(t, roundId);
        ChoiceList list = choiceLists.findByStudentCounsellingIdAndRoundId(t.getId(), round.getId())
                .orElseGet(() -> {
                    ChoiceList cl = new ChoiceList();
                    cl.setStudentCounselling(t);
                    cl.setRound(round);
                    return choiceLists.save(cl);
                });
        return toListResponse(list);
    }

    @Transactional(readOnly = true)
    public ChoiceListResponse getChoiceList(Long listId) {
        ChoiceList list = choiceLists.findById(listId).orElseThrow(() -> ApiException.notFound("Choice list"));
        studentService.requireReadable(list.getStudentCounselling().getStudent().getId());
        return toListResponse(list);
    }

    /** Replaces the whole ordered list; position = index + 1. Only drafts can change. */
    @Transactional
    public ChoiceListResponse saveItems(Long listId, List<ChoiceItemRequest> items) {
        ChoiceList list = writableDraft(listId);
        CounsellingAuthority authority = list.getStudentCounselling().getAuthority();
        Set<String> seen = new HashSet<>();
        Map<Long, College> byId = new LinkedHashMap<>();
        colleges.findAllById(items.stream().map(ChoiceItemRequest::collegeId).distinct().toList())
                .forEach(c -> byId.put(c.getId(), c));
        for (int i = 0; i < items.size(); i++) {
            ChoiceItemRequest it = items.get(i);
            College c = byId.get(it.collegeId());
            if (c == null) {
                throw ApiException.badRequest("Choice " + (i + 1) + ": college not found");
            }
            if (!seen.add(c.getId() + "|" + it.course() + "|" + it.quota())) {
                throw ApiException.badRequest("Choice " + (i + 1) + " repeats an earlier choice (" + c.getName()
                        + ", " + it.course() + ", " + it.quota() + ")");
            }
            checkQuotaFits(authority, c, it.quota(), i + 1);
        }
        // Delete first and flush, so re-used positions do not collide with the unique constraint.
        list.getItems().clear();
        choiceLists.saveAndFlush(list);
        for (int i = 0; i < items.size(); i++) {
            ChoiceItemRequest it = items.get(i);
            list.getItems().add(new ChoiceListItem(list, i + 1, byId.get(it.collegeId()), it.course(), it.quota(),
                    blankToNull(it.note())));
        }
        choiceLists.saveAndFlush(list);
        return toListResponse(list);
    }

    /** Starts this round's draft from another round's list of the same track (e.g. Round 2 from Round 1). */
    @Transactional
    public ChoiceListResponse copyFrom(Long listId, Long sourceListId) {
        ChoiceList target = writableDraft(listId);
        ChoiceList source = choiceLists.findById(sourceListId)
                .orElseThrow(() -> ApiException.notFound("Source choice list"));
        if (!source.getStudentCounselling().getId().equals(target.getStudentCounselling().getId())) {
            throw ApiException.badRequest("Can only copy from the same student's track");
        }
        List<ChoiceItemRequest> items = source.getItems().stream()
                .map(i -> new ChoiceItemRequest(i.getCollege().getId(), i.getCourse(), i.getQuota(), i.getNote()))
                .toList();
        return saveItems(listId, items);
    }

    /**
     * Freezes the final order exactly as submitted to the authority, recording who confirmed it and how
     * (spec 4.5: choice-list lock confirmation logging for dispute protection).
     */
    @Transactional
    public ChoiceListResponse lock(Long listId, LockRequest req) {
        ChoiceList list = writableDraft(listId);
        if (list.getItems().isEmpty()) {
            throw ApiException.badRequest("Add at least one choice before locking");
        }
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (ChoiceListItem i : list.getItems()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("position", i.getPosition());
            row.put("collegeId", i.getCollege().getId());
            row.put("college", i.getCollege().getName());
            row.put("code", i.getCollege().getCode());
            row.put("course", i.getCourse());
            row.put("quota", i.getQuota());
            snapshot.add(row);
        }
        String json = mapper.writeValueAsString(snapshot);
        CurrentUser me = me();
        list.lock(users.getReferenceById(me.id()), req.confirmedByName().trim(), req.confirmationMethod(), json);
        StudentCounselling t = list.getStudentCounselling();
        if (t.getStatus() == CounsellingStatus.NOT_REGISTERED || t.getStatus() == CounsellingStatus.REGISTERED) {
            t.setStatus(CounsellingStatus.CHOICES_FILLED);
        }
        audit.record(me.id(), "CHOICE_LIST_LOCKED", "CHOICE_LIST", list.getId(), "confirmedBy="
                + req.confirmedByName().trim() + " via " + req.confirmationMethod() + "; order=" + json);
        return toListResponse(list);
    }

    /** Admin-only escape hatch, e.g. the authority reopened choice filling. The reason is audited. */
    @Transactional
    public ChoiceListResponse unlock(Long listId, String reason) {
        CurrentUser me = me();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can unlock a locked choice list");
        }
        ChoiceList list = choiceLists.findById(listId).orElseThrow(() -> ApiException.notFound("Choice list"));
        if (list.getStatus() != ChoiceListStatus.LOCKED) {
            throw ApiException.badRequest("This list is not locked");
        }
        audit.record(me.id(), "CHOICE_LIST_UNLOCKED", "CHOICE_LIST", list.getId(), "reason=" + reason.trim()
                + "; previousOrder=" + list.getLockedSnapshot());
        list.unlock();
        return toListResponse(list);
    }

    private ChoiceListResponse toListResponse(ChoiceList list) {
        StudentCounselling t = list.getStudentCounselling();
        return new ChoiceListResponse(list.getId(), t.getId(), t.getStudent().getId(), t.getStudent().getFullName(),
                AuthorityRef.of(t.getAuthority()), RoundResponse.of(list.getRound()), list.getStatus(),
                list.getLockedAt(), UserRef.of(list.getLockedBy()), list.getConfirmedByName(),
                list.getConfirmationMethod(), list.getItems().stream().map(ChoiceItemResponse::of).toList(),
                list.getUpdatedAt());
    }

    private static void checkQuotaFits(CounsellingAuthority authority, College college, Quota quota, int position) {
        if (authority.getAuthorityType() == AuthorityType.STATE) {
            if (!Objects.equals(authority.getState(), college.getState())) {
                throw ApiException.badRequest("Choice " + position + ": " + college.getName() + " is in "
                        + college.getState() + ", not part of " + authority.getCode() + " counselling");
            }
            if (quota == Quota.AIQ || quota == Quota.DEEMED) {
                throw ApiException.badRequest("Choice " + position + ": " + quota + " seats are filled by MCC, not "
                        + authority.getCode());
            }
        } else if (quota == Quota.STATE) {
            throw ApiException.badRequest("Choice " + position + ": state-quota seats are filled by the state "
                    + "authority, not " + authority.getCode());
        }
    }

    // ================================================================== allotments & decisions

    @Transactional
    public AllotmentResponse recordAllotment(Long trackId, AllotmentRequest req) {
        StudentCounselling t = writableTrack(trackId);
        return AllotmentResponse.of(record(t, req, me()));
    }

    /** Shared by single entry and bulk upload. Raises the result alerts immediately (spec 4.6, 4.9). */
    AllotmentResult record(StudentCounselling t, AllotmentRequest req, CurrentUser me) {
        CounsellingRoundEntity round = roundOfTrack(t, req.roundId());
        College college = null;
        if (req.collegeId() != null) {
            college = colleges.findById(req.collegeId())
                    .orElseThrow(() -> ApiException.badRequest("College not found"));
            if (req.course() == null || req.quota() == null) {
                throw ApiException.badRequest("Course and quota are required for an allotted seat");
            }
        }
        AllotmentResult a = allotments.findByStudentCounsellingIdAndRoundId(t.getId(), round.getId())
                .orElseGet(() -> {
                    AllotmentResult n = new AllotmentResult();
                    n.setStudentCounselling(t);
                    n.setRound(round);
                    return n;
                });
        Student s = t.getStudent();
        a.record(college, college == null ? null : req.course(), college == null ? null : req.quota(),
                college == null ? null : (req.category() != null ? req.category() : s.getCategory()),
                users.getReferenceById(me.id()), round.getReportingEnd());
        allotments.save(a);
        if (college != null && t.getStatus() != CounsellingStatus.ADMITTED) {
            t.setStatus(CounsellingStatus.ALLOTTED);
        }
        audit.record(me.id(), "ALLOTMENT_RECORDED", "ALLOTMENT", a.getId(), round.label() + ": "
                + (college == null ? "no allotment" : college.getName() + " " + req.course() + " " + req.quota()));

        String key = "ALLOT:" + a.getId() + ":" + (college == null ? "none" : college.getId() + ":" + req.quota());
        String link = "/students/" + s.getId() + "?tab=counselling";
        if (college != null) {
            alerts.messageFamily(s, "ALLOTMENT_RESULT", Priority.URGENT,
                    texts.allotted(s, round, college, req.quota(), a.getDecisionDeadline()), key);
            alerts.notifyStaffFor(s, "ALLOTMENT", Priority.URGENT,
                    s.getFullName() + ": seat at " + college.getName(),
                    round.label() + " - " + req.quota() + ". Decision (freeze / float / withdraw) needed by "
                            + AlertTexts.when(a.getDecisionDeadline()) + ".", link, key);
        } else {
            alerts.messageFamily(s, "NO_ALLOTMENT", Priority.NORMAL, texts.notAllotted(s, round), key);
            alerts.notifyStaffFor(s, "NO_ALLOTMENT", Priority.NORMAL, s.getFullName() + ": no seat in "
                    + round.label(), "Plan the next round's choices.", link, key);
        }
        return a;
    }

    @Transactional(readOnly = true)
    public DecisionPreview previewDecision(Long allotmentId, Decision decision) {
        AllotmentResult a = allotments.findById(allotmentId).orElseThrow(() -> ApiException.notFound("Allotment"));
        studentService.requireReadable(a.getStudentCounselling().getStudent().getId());
        List<String> lines = new ArrayList<>();
        boolean ruleFound = false;
        if (decision == Decision.WITHDRAW) {
            RefundRuleEngine.Assessment r = refundRules.assess(new RefundRuleEngine.Seat(a.getRound().getAcademicYear(),
                    a.getRound().getAuthority().getId(), a.getCollege() == null ? null : a.getCollege().getId(),
                    a.getRound().getRoundType(), a.getRecordedAt()), Instant.now());
            ruleFound = r.ruleFound();
            lines.addAll(r.lines());
        }
        List<String> general = consequences(a, decision);
        // With a recorded rule, drop the generic deposit guess (first WITHDRAW line) in favour of the exact rule.
        lines.addAll(ruleFound ? general.subList(1, general.size()) : general);
        return new DecisionPreview(decision, lines, isPastDeadline(a), ruleFound);
    }

    @Transactional
    public AllotmentResponse decide(Long allotmentId, DecisionRequest req) {
        AllotmentResult a = allotments.findById(allotmentId).orElseThrow(() -> ApiException.notFound("Allotment"));
        StudentCounselling t = a.getStudentCounselling();
        studentService.requireWritable(t.getStudent().getId());
        CurrentUser me = me();
        if (!a.isAllotted()) {
            throw ApiException.badRequest("There is no seat to decide on in this round");
        }
        if (isPastDeadline(a) && !me.isAdmin()) {
            throw ApiException.badRequest("The reporting deadline has passed; ask an admin to record this decision");
        }
        a.decide(req.decision(), users.getReferenceById(me.id()), blankToNull(req.note()));
        t.setStatus(switch (req.decision()) {
            case FREEZE -> CounsellingStatus.ADMITTED;
            case FLOAT -> CounsellingStatus.ALLOTTED;
            case WITHDRAW -> CounsellingStatus.REGISTERED;
        });
        audit.record(me.id(), "DECISION_RECORDED", "ALLOTMENT", a.getId(), req.decision() + " for "
                + a.getCollege().getName() + " (" + a.getRound().label() + ")"
                + (req.note() == null ? "" : "; note=" + req.note()));
        return AllotmentResponse.of(a);
    }

    /**
     * General rules shown before confirming. Phase 3's refund rules engine (spec 4.27) replaces these with
     * college- and round-specific amounts.
     */
    static List<String> consequences(AllotmentResult a, Decision decision) {
        List<String> out = new ArrayList<>();
        String authority = a.getRound().getAuthority().getCode();
        CounsellingRound round = a.getRound().getRoundType();
        String deadline = AlertTexts.when(a.getDecisionDeadline());
        switch (decision) {
            case FREEZE -> {
                out.add("The student keeps this seat and exits further " + authority + " rounds; no upgrades.");
                out.add("Must report to the college with original documents and fees by " + deadline + ".");
            }
            case FLOAT -> {
                out.add("The student reports and holds this seat but stays in the running for a better choice "
                        + "in the next round.");
                out.add("If upgraded, this seat is released automatically and cannot be reclaimed.");
                out.add("Must report by " + deadline + " or the seat is lost.");
            }
            case WITHDRAW -> {
                if (round == CounsellingRound.ROUND_1) {
                    out.add("Round 1 seats can generally be given up without forfeiting the security deposit, "
                            + "and the student stays eligible for Round 2.");
                } else {
                    out.add("WARNING: seats allotted from Round 2 onward generally cannot be given up without "
                            + "forfeiting the security deposit, and the student may be barred from later rounds.");
                }
                out.add("The seat goes to another candidate immediately and cannot be reclaimed.");
            }
        }
        out.add("Confirm with the current " + authority + " notification before acting. These are general rules.");
        return out;
    }

    private static boolean isPastDeadline(AllotmentResult a) {
        return a.getDecisionDeadline() != null && a.getDecisionDeadline().isBefore(Instant.now());
    }

    // ================================================================== round desk

    /** Live view for result days (spec 4.13): deadlines, undecided seats, unanswered urgent alerts. */
    @Transactional(readOnly = true)
    public RoundDesk desk() {
        CurrentUser me = me();
        if (me.role() != Role.SUPER_ADMIN && me.role() != Role.COUNSELLOR) {
            throw ApiException.forbidden("The round desk is for counsellors and admins");
        }
        List<DeskRow> pending = allotments.findAllUndecidedOpen(Instant.now()).stream()
                .filter(a -> me.isAdmin() || isMine(a.getStudentCounselling().getStudent(), me))
                .map(DeskRow::of).toList();
        return new RoundDesk(CalendarController.upcoming(rounds, Instant.now(), 7), pending,
                messages.countByPriorityAndAcknowledgedAtIsNullAndEscalatedAtIsNotNull(Priority.URGENT));
    }

    // ================================================================== helpers

    private StudentCounselling writableTrack(Long trackId) {
        StudentCounselling t = tracks.findById(trackId).orElseThrow(() -> ApiException.notFound("Counselling track"));
        studentService.requireWritable(t.getStudent().getId());
        return t;
    }

    private ChoiceList writableDraft(Long listId) {
        ChoiceList list = choiceLists.findById(listId).orElseThrow(() -> ApiException.notFound("Choice list"));
        studentService.requireWritable(list.getStudentCounselling().getStudent().getId());
        if (list.getStatus() == ChoiceListStatus.LOCKED) {
            throw ApiException.conflict("This choice list is locked. An admin must unlock it to make changes.");
        }
        return list;
    }

    private CounsellingRoundEntity roundOfTrack(StudentCounselling t, Long roundId) {
        CounsellingRoundEntity round = rounds.findById(roundId).orElseThrow(() -> ApiException.notFound("Round"));
        if (!round.getAuthority().getId().equals(t.getAuthority().getId())
                || round.getAcademicYear() != t.getAcademicYear()) {
            throw ApiException.badRequest("That round belongs to a different authority or year");
        }
        return round;
    }

    private static boolean isMine(Student s, CurrentUser me) {
        AppUser c = s.getAssignedCounsellor();
        return c != null && c.getId().equals(me.id());
    }

    static CurrentUser me() {
        return CurrentUser.get();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
