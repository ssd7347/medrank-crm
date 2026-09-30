package com.mbbscrm.crm.portal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.mbbscrm.crm.agreement.AgreementService;
import com.mbbscrm.crm.agreement.AgreementService.AgreementView;
import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;
import com.mbbscrm.crm.counselling.AuthorityType;
import com.mbbscrm.crm.counselling.CounsellingDtos.AllotmentResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.RoundResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.TrackResponse;
import com.mbbscrm.crm.counselling.CounsellingService;
import com.mbbscrm.crm.counselling.CounsellingStatus;
import com.mbbscrm.crm.counselling.Decision;
import com.mbbscrm.crm.document.DocumentService;
import com.mbbscrm.crm.document.DocumentService.Checklist;
import com.mbbscrm.crm.document.DocumentStatus;
import com.mbbscrm.crm.engagement.SessionService;
import com.mbbscrm.crm.engagement.SessionService.SessionView;
import com.mbbscrm.crm.loan.LoanService;
import com.mbbscrm.crm.loan.LoanService.LoanView;
import com.mbbscrm.crm.fee.FeeService;
import com.mbbscrm.crm.fee.FeeService.InstallmentView;
import com.mbbscrm.crm.fee.FeeService.PlanView;
import com.mbbscrm.crm.fee.PaymentMethod;
import com.mbbscrm.crm.fee.PlanStatus;
import com.mbbscrm.crm.helpdesk.TicketCategory;
import com.mbbscrm.crm.helpdesk.TicketService;
import com.mbbscrm.crm.helpdesk.TicketStatus;
import com.mbbscrm.crm.portal.PortalAccountStudent.Relation;
import com.mbbscrm.crm.predictor.PredictorShortlistRepository;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.user.AppUser;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * What a student or parent sees in the portal (spec 4.10): status of each counselling track side by side,
 * the next deadline, shortlist, document checklist, fees, and their questions. Everything is read through
 * the login-to-student link, and only family-appropriate fields are returned (no internal notes).
 */
@Service
public class PortalService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PortalAccountRepository accounts;
    private final PortalAccountStudentRepository links;
    private final PortalSessionService sessions;
    private final CounsellingService counselling;
    private final DocumentService documents;
    private final FeeService fees;
    private final TicketService tickets;
    private final PredictorShortlistRepository shortlist;
    private final AlertService alerts;
    private final AuditService audit;
    private final String orgName;
    private final LoanService loans;
    private final SessionService sessionService;
    private final AgreementService agreements;

    public PortalService(PortalAccountRepository accounts, PortalAccountStudentRepository links,
                         PortalSessionService sessions, CounsellingService counselling, DocumentService documents,
                         FeeService fees, TicketService tickets, PredictorShortlistRepository shortlist,
                         AlertService alerts, AuditService audit,
                         @Value("${app.org-name}") String orgName, LoanService loans,
                         SessionService sessionService, AgreementService agreements) {
        this.loans = loans;
        this.sessionService = sessionService;
        this.agreements = agreements;
        this.accounts = accounts;
        this.links = links;
        this.sessions = sessions;
        this.counselling = counselling;
        this.documents = documents;
        this.fees = fees;
        this.tickets = tickets;
        this.shortlist = shortlist;
        this.alerts = alerts;
        this.audit = audit;
        this.orgName = orgName;
    }

    // ------------------------------------------------------------------ DTOs

    public record StudentLink(Long id, String fullName, Relation relation) {
    }

    public record Me(String displayName, String phone, String orgName, List<StudentLink> students) {
    }

    public record StudentCard(Long id, String fullName, Category category, String homeState, Integer neetScore,
                              Integer neetAir, String counsellorName, String counsellorPhone) {
    }

    public record Deadline(String title, String detail, Instant at) {
    }

    public record AllotmentView(String roundLabel, boolean allotted, String collegeName, Course course, Quota quota,
                                Decision decision, Instant decisionDeadline) {
    }

    public record TrackView(String authorityName, AuthorityType authorityType, int academicYear,
                            CounsellingStatus status, String registrationNo, String currentRound,
                            String currentPhase, List<AllotmentView> allotments) {
    }

    public record ShortlistRow(String collegeName, String state, Course course, Quota quota, String band) {
    }

    public record DocumentRow(Long typeId, String name, boolean required, DocumentStatus status, LocalDate validUntil,
                              boolean expired, int files) {
    }

    public record DocumentsView(int requiredCount, int requiredDone, List<DocumentRow> items) {
    }

    public record InstalmentRow(String label, BigDecimal amount, LocalDate dueDate, BigDecimal balance, String state) {
    }

    public record PaymentRow(String receiptNo, BigDecimal amount, LocalDate paidOn, PaymentMethod method) {
    }

    public record FeesView(BigDecimal total, BigDecimal paid, BigDecimal balance, List<InstalmentRow> instalments,
                           List<PaymentRow> payments) {
    }

    public record TicketRow(Long id, String subject, TicketCategory category, TicketStatus status, Instant createdAt,
                            String resolution) {
    }

    public record LoanRow(String lender, BigDecimal amountRequested, BigDecimal amountSanctioned, String status,
                          LocalDate neededBy, String risk) {
    }

    public record SessionRow(Long id, String topic, String mode, Instant scheduledAt, int durationMinutes,
                             String meetingUrl, String hostName) {
    }

    public record AgreementRow(Long id, String title, String status, String body, String signerName,
                               Instant signedAt) {
        static AgreementRow of(AgreementView a) {
            return new AgreementRow(a.id(), a.title(), a.status().name(), a.body(), a.signerName(), a.signedAt());
        }
    }

    public record AcceptRequest(@NotBlank @Size(min = 3, max = 120) String typedName, boolean agreed) {
    }

    public record Overview(StudentCard student, Deadline nextDeadline, List<TrackView> tracks,
                           List<ShortlistRow> shortlist, DocumentsView documents, FeesView fees,
                           List<TicketRow> tickets, List<LoanRow> loans, List<SessionRow> sessions,
                           List<AgreementRow> agreements) {
    }

    public record QuestionRequest(@NotBlank @Size(max = 200) String subject, @Size(max = 4000) String description,
                                  @NotNull TicketCategory category) {
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Me me() {
        PortalAccount account = account();
        List<StudentLink> students = links.findByAccountIdOrderByIdAsc(account.getId()).stream()
                .map(l -> new StudentLink(l.getStudent().getId(), l.getStudent().getFullName(), l.getRelation()))
                .toList();
        return new Me(account.getDisplayName(), account.getPhone(), orgName, students);
    }

    @Transactional(readOnly = true)
    public Overview overview(Long studentId) {
        Student s = linked(studentId).getStudent();
        AppUser counsellor = s.getAssignedCounsellor();
        StudentCard card = new StudentCard(s.getId(), s.getFullName(), s.getCategory(), s.getHomeState(),
                s.getNeetScore(), s.getNeetAir(), counsellor == null ? null : counsellor.getFullName(),
                counsellor == null ? null : counsellor.getPhone());

        Instant now = Instant.now();
        List<Deadline> upcoming = new ArrayList<>();
        List<TrackView> tracks = new ArrayList<>();
        for (TrackResponse t : counselling.tracksForStudent(s)) {
            RoundResponse current = currentRound(t.rounds(), now);
            for (RoundResponse r : t.rounds()) {
                add(upcoming, r.label() + ": choice filling closes", "Your counsellor must lock your choices before this.",
                        r.choiceFillingEnd(), now);
                add(upcoming, r.label() + ": result", "Allotment result is expected.", r.resultAt(), now);
                add(upcoming, r.label() + ": reporting closes", "Last date to report at the allotted college.",
                        r.reportingEnd(), now);
            }
            List<AllotmentView> allotments = new ArrayList<>();
            for (AllotmentResponse a : t.allotments()) {
                if (a.recordedAt() == null) {
                    continue;
                }
                allotments.add(new AllotmentView(a.roundLabel(), a.college() != null,
                        a.college() == null ? null : a.college().name(), a.course(), a.quota(), a.decision(),
                        a.decisionDeadline()));
                if (a.college() != null && a.decision() == null) {
                    add(upcoming, "Decide on your " + a.college().name() + " seat",
                            "Tell your counsellor whether to accept, try for a better seat, or give it up.",
                            a.decisionDeadline(), now);
                }
            }
            tracks.add(new TrackView(t.authority().name(), t.authority().authorityType(), t.academicYear(),
                    t.status(), t.registrationNo(), current == null ? null : current.label(),
                    current == null ? null : current.phase(), allotments));
        }

        FeesView feesView = fees(s, upcoming, now);
        Checklist checklist = documents.buildChecklist(s);
        DocumentsView docs = new DocumentsView(checklist.requiredCount(), checklist.requiredDone(),
                checklist.items().stream().map(i -> new DocumentRow(i.typeId(), i.name(), i.required(), i.status(),
                        i.validUntil(), i.expired(), i.files().size())).toList());
        List<ShortlistRow> shortlisted = shortlist.findByStudentIdOrderByCreatedAtAsc(s.getId()).stream()
                .map(p -> new ShortlistRow(p.getCollege().getName(), p.getCollege().getState(), p.getCourse(),
                        p.getQuota(), p.getBand() == null ? null : p.getBand().name())).toList();
        List<TicketRow> ticketRows = tickets.forStudentUnchecked(s.getId()).stream()
                .map(t -> new TicketRow(t.id(), t.subject(), t.category(), t.status(), t.createdAt(), t.resolution()))
                .toList();

        List<LoanRow> loanRows = loans.forStudentUnchecked(s.getId()).stream()
                .filter(l -> l.status() != com.mbbscrm.crm.loan.LoanApplication.Status.WITHDRAWN)
                .map((LoanView l) -> new LoanRow(l.partnerName(), l.amountRequested(), l.amountSanctioned(),
                        l.status().name(), l.neededBy(), l.risk())).toList();
        List<SessionRow> sessionRows = sessionService.upcomingForStudentUnchecked(s.getId()).stream()
                .map((SessionView v) -> new SessionRow(v.id(), v.topic(), v.mode().name(), v.scheduledAt(),
                        v.durationMinutes(), v.meetingUrl(), v.host() == null ? null : v.host().fullName())).toList();
        for (SessionRow r : sessionRows) {
            add(upcoming, "Counselling session: " + r.topic(), r.hostName() == null ? "" : "With " + r.hostName() + ".",
                    r.scheduledAt(), now);
        }
        List<AgreementRow> agreementRows = agreements.forPortal(s.getId()).stream().map(AgreementRow::of).toList();

        Deadline next = upcoming.stream().min(Comparator.comparing(Deadline::at)).orElse(null);
        return new Overview(card, next, tracks, shortlisted, docs, feesView, ticketRows, loanRows, sessionRows,
                agreementRows);
    }

    private FeesView fees(Student s, List<Deadline> upcoming, Instant now) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal balance = BigDecimal.ZERO;
        List<InstalmentRow> instalments = new ArrayList<>();
        List<PaymentRow> payments = new ArrayList<>();
        for (PlanView p : fees.plansForStudent(s)) {
            if (p.status() == PlanStatus.CANCELLED) {
                continue;
            }
            total = total.add(p.netAmount());
            paid = paid.add(p.paid());
            balance = balance.add(p.balance());
            for (InstallmentView i : p.installments()) {
                instalments.add(new InstalmentRow(i.label(), i.amount(), i.dueDate(), i.balance(), i.state()));
                if (i.balance().signum() > 0 && p.status() == PlanStatus.ACTIVE) {
                    // Due dates count until the end of that day, Indian time.
                    add(upcoming, "Fee instalment due: " + i.label(), "Balance ₹" + i.balance().toPlainString(),
                            i.dueDate().plusDays(1).atStartOfDay(IST).toInstant(), now);
                }
            }
            p.payments().stream().filter(x -> !x.voided())
                    .forEach(x -> payments.add(new PaymentRow(x.receiptNo(), x.amount(), x.paidOn(), x.method())));
        }
        return new FeesView(total, paid, balance, instalments, payments);
    }

    // ------------------------------------------------------------------ actions

    @Transactional
    public DocumentsView upload(Long studentId, Long typeId, MultipartFile file) {
        PortalAccountStudent link = linked(studentId);
        Student s = link.getStudent();
        Checklist checklist = documents.uploadFromPortal(s, typeId, file, link.getAccount().getId());
        String typeName = checklist.items().stream().filter(i -> i.typeId().equals(typeId)).map(i -> i.name())
                .findFirst().orElse("a document");
        alerts.notifyStaffFor(s, "PORTAL_UPLOAD", Priority.NORMAL, s.getFullName() + " uploaded " + typeName,
                "Sent through the portal. Please check and verify it.", "/students/" + s.getId() + "?tab=documents",
                null);
        return new DocumentsView(checklist.requiredCount(), checklist.requiredDone(),
                checklist.items().stream().map(i -> new DocumentRow(i.typeId(), i.name(), i.required(), i.status(),
                        i.validUntil(), i.expired(), i.files().size())).toList());
    }

    @Transactional
    public TicketRow ask(Long studentId, QuestionRequest req) {
        PortalAccountStudent link = linked(studentId);
        long open = tickets.forStudentUnchecked(studentId).stream()
                .filter(t -> t.status() != TicketStatus.RESOLVED && t.status() != TicketStatus.CLOSED).count();
        if (open >= 10) {
            throw ApiException.badRequest("You already have several open questions. Please wait for a reply or call "
                    + "your counsellor.");
        }
        var t = tickets.createFromPortal(link.getStudent(), link.getAccount().getDisplayName(), req.subject(),
                req.description(), req.category(), link.getAccount().getId());
        return new TicketRow(t.id(), t.subject(), t.category(), t.status(), t.createdAt(), t.resolution());
    }

    /** The family accepts an agreement from inside their own login (see AgreementService). */
    @Transactional
    public AgreementRow accept(Long studentId, Long agreementId, AcceptRequest req, String ip) {
        if (!req.agreed()) {
            throw ApiException.badRequest("Tick the box to confirm you have read and agree");
        }
        PortalAccountStudent link = linked(studentId);
        return AgreementRow.of(agreements.signFromPortal(link.getStudent(), agreementId, req.typedName(),
                link.getRelation().name(), ip, link.getAccount().getId()));
    }

    // ------------------------------------------------------------------ helpers

    private PortalAccount account() {
        return accounts.findById(PortalUser.get().accountId()).filter(PortalAccount::isActive)
                .orElseThrow(() -> ApiException.forbidden("This login is no longer active"));
    }

    /** The link proving this login may see this student; anything else looks like "not found". */
    private PortalAccountStudent linked(Long studentId) {
        PortalAccount account = account();
        return links.findByAccountIdAndStudentId(account.getId(), studentId)
                .orElseThrow(() -> ApiException.notFound("Student"));
    }

    /** The round that is in progress, else the next one to start, else the last one. */
    private static RoundResponse currentRound(List<RoundResponse> rounds, Instant now) {
        RoundResponse last = null;
        for (RoundResponse r : rounds) {
            Instant end = r.reportingEnd() != null ? r.reportingEnd() : r.resultAt() != null ? r.resultAt()
                    : r.choiceFillingEnd();
            if (end == null || end.isAfter(now)) {
                return r;
            }
            last = r;
        }
        return last;
    }

    private static void add(List<Deadline> list, String title, String detail, Instant at, Instant now) {
        if (at != null && at.isAfter(now)) {
            list.add(new Deadline(title, detail, at));
        }
    }
}
