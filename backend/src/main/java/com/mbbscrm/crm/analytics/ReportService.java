package com.mbbscrm.crm.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alumni.Alumni;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.counselling.AllotmentResult;
import com.mbbscrm.crm.fee.Payment;
import com.mbbscrm.crm.helpdesk.Ticket;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.user.AppUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/**
 * Report builder (spec 4.13): returns one dataset as plain rows for a period and branch. The browser lets the
 * user pick columns and saves the result as a spreadsheet or prints it to PDF. Every run is audited because
 * the rows contain phone numbers.
 */
@Service
public class ReportService {

    public enum Dataset {
        LEADS, STUDENTS, PAYMENTS, ADMISSIONS, ALLOTMENTS, TICKETS
    }

    static final int MAX_ROWS = 5000;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(IST);

    private final EntityManager em;
    private final AuditService audit;

    public ReportService(EntityManager em, AuditService audit) {
        this.em = em;
        this.audit = audit;
    }

    public record Column(String key, String label) {
    }

    public record Report(Dataset dataset, LocalDate from, LocalDate to, List<Column> columns, List<List<Object>> rows,
                         boolean truncated) {
    }

    @Transactional(readOnly = true)
    public Report run(Dataset dataset, LocalDate from, LocalDate to, Long branchId) {
        LocalDate end = to == null ? LocalDate.now(IST) : to;
        LocalDate start = from == null ? end.minusYears(1) : from;
        if (start.isAfter(end)) {
            throw ApiException.badRequest("The start date must be before the end date");
        }
        Instant since = start.atStartOfDay(IST).toInstant();
        Instant until = end.plusDays(1).atStartOfDay(IST).toInstant();

        List<Column> columns;
        List<List<Object>> rows = new ArrayList<>();
        switch (dataset) {
            case LEADS -> {
                columns = cols("id", "Lead #", "name", "Name", "phone", "Phone", "source", "Source", "campaign",
                        "Campaign", "associate", "Referral associate", "status", "Status", "counsellor", "Assigned to",
                        "branch", "Branch", "category", "Category", "state", "Home state", "score", "NEET score",
                        "air", "AIR", "created", "Created on");
                for (Lead l : timed("select l from Lead l left join fetch l.assignedCounsellor left join fetch l.branch "
                        + "left join fetch l.campaign left join fetch l.referralAssociate "
                        + "where l.createdAt >= :since and l.createdAt < :until", "l.branch.id",
                        " order by l.createdAt desc", Lead.class, since, until, branchId)) {
                    rows.add(row(l.getId(), l.getFullName(), l.getPhone(), l.getSource(),
                            l.getCampaign() == null ? null : l.getCampaign().getName(),
                            l.getReferralAssociate() == null ? null : l.getReferralAssociate().getFullName(),
                            l.getStatus(), name(l.getAssignedCounsellor()),
                            l.getBranch() == null ? null : l.getBranch().getName(), l.getCategory(), l.getHomeState(),
                            l.getNeetScore(), l.getNeetAir(), DAY.format(l.getCreatedAt())));
                }
            }
            case STUDENTS -> {
                columns = cols("id", "Student #", "name", "Name", "phone", "Phone", "parentPhone", "Parent phone",
                        "category", "Category", "state", "Home state", "score", "NEET score", "air", "AIR",
                        "counsellor", "Counsellor", "branch", "Branch", "created", "Added on");
                for (Student s : timed("select s from Student s left join fetch s.assignedCounsellor "
                        + "left join fetch s.branch where s.createdAt >= :since and s.createdAt < :until", "s.branch.id",
                        " order by s.createdAt desc", Student.class, since, until, branchId)) {
                    rows.add(row(s.getId(), s.getFullName(), s.getPhone(), s.getParentPhone(), s.getCategory(),
                            s.getHomeState(), s.getNeetScore(), s.getNeetAir(), name(s.getAssignedCounsellor()),
                            s.getBranch() == null ? null : s.getBranch().getName(), DAY.format(s.getCreatedAt())));
                }
            }
            case PAYMENTS -> {
                columns = cols("receipt", "Receipt no.", "date", "Paid on", "student", "Student", "plan", "Fee plan",
                        "amount", "Amount", "method", "Method", "reference", "Reference", "receivedBy", "Received by",
                        "voided", "Voided");
                TypedQuery<Payment> q = em.createQuery("select x from Payment x join fetch x.plan p join fetch p.student s "
                        + "left join fetch x.receivedBy where x.paidOn >= :start and x.paidOn <= :end"
                        + (branchId == null ? "" : " and s.branch.id = :branchId") + " order by x.paidOn desc, x.id desc",
                        Payment.class).setParameter("start", start).setParameter("end", end);
                for (Payment p : limited(q, branchId)) {
                    rows.add(row(p.getReceiptNo(), p.getPaidOn(), p.getPlan().getStudent().getFullName(),
                            p.getPlan().getName(), p.getAmount(), p.getMethod(), p.getReference(),
                            name(p.getReceivedBy()), p.isVoided() ? "Yes" : "No"));
                }
            }
            case ADMISSIONS -> {
                columns = cols("student", "Student", "phone", "Phone", "college", "College", "course", "Course",
                        "quota", "Quota", "year", "Admission year", "counsellor", "Counsellor", "branch", "Branch",
                        "willing", "Willing to refer");
                for (Alumni a : timed("select a from Alumni a join fetch a.student s left join fetch s.assignedCounsellor "
                        + "left join fetch s.branch where a.createdAt >= :since and a.createdAt < :until", "s.branch.id",
                        " order by a.createdAt desc", Alumni.class, since, until, branchId)) {
                    Student s = a.getStudent();
                    rows.add(row(s.getFullName(), s.getPhone(), a.getCollegeName(), a.getCourse(), a.getQuota(),
                            a.getAdmissionYear(), name(s.getAssignedCounsellor()),
                            s.getBranch() == null ? null : s.getBranch().getName(), a.isWillingToRefer() ? "Yes" : "No"));
                }
            }
            case ALLOTMENTS -> {
                columns = cols("student", "Student", "round", "Round", "college", "College", "course", "Course",
                        "quota", "Quota", "decision", "Decision", "deadline", "Decision deadline", "recorded",
                        "Recorded on", "counsellor", "Counsellor");
                for (AllotmentResult a : timed("select a from AllotmentResult a join fetch a.studentCounselling sc "
                        + "join fetch sc.student s left join fetch s.assignedCounsellor join fetch a.round r "
                        + "join fetch r.authority left join fetch a.college "
                        + "where a.recordedAt >= :since and a.recordedAt < :until", "s.branch.id",
                        " order by a.recordedAt desc", AllotmentResult.class, since, until, branchId)) {
                    Student s = a.getStudentCounselling().getStudent();
                    rows.add(row(s.getFullName(), a.getRound().label(),
                            a.getCollege() == null ? "Not allotted" : a.getCollege().getName(), a.getCourse(),
                            a.getQuota(), a.getDecision(),
                            a.getDecisionDeadline() == null ? null : DAY.format(a.getDecisionDeadline()),
                            DAY.format(a.getRecordedAt()), name(s.getAssignedCounsellor())));
                }
            }
            case TICKETS -> {
                columns = cols("id", "Ticket #", "subject", "Subject", "category", "Category", "priority", "Priority",
                        "status", "Status", "student", "Student", "via", "Raised via", "assignedTo", "Assigned to",
                        "created", "Created on", "due", "Respond by", "resolved", "Resolved on");
                for (Ticket t : timed("select t from Ticket t left join fetch t.student s left join fetch t.assignedTo "
                        + "where t.createdAt >= :since and t.createdAt < :until", "s.branch.id",
                        " order by t.createdAt desc", Ticket.class, since, until, branchId)) {
                    rows.add(row(t.getId(), t.getSubject(), t.getCategory(), t.getPriority(), t.getStatus(),
                            t.getStudent() == null ? t.getRaisedByName() : t.getStudent().getFullName(),
                            t.getRaisedVia(), name(t.getAssignedTo()), DAY.format(t.getCreatedAt()),
                            DAY.format(t.getDueAt()), t.getResolvedAt() == null ? null : DAY.format(t.getResolvedAt())));
                }
            }
            default -> throw ApiException.badRequest("Unknown report");
        }
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_ROWS);
        }
        audit.recordStandalone(CurrentUser.get().id(), "REPORT_RUN", "REPORT", null,
                dataset + " " + start + ".." + end + (branchId == null ? "" : " branch=" + branchId) + " rows="
                        + rows.size());
        return new Report(dataset, start, end, columns, rows, truncated);
    }

    private <T> List<T> timed(String select, String branchPath, String tail, Class<T> type, Instant since,
                              Instant until, Long branchId) {
        TypedQuery<T> q = em.createQuery(select + (branchId == null ? "" : " and " + branchPath + " = :branchId") + tail,
                type).setParameter("since", since).setParameter("until", until);
        return limited(q, branchId);
    }

    /** Reads one row more than the limit so the caller can tell the user the report was cut short. */
    private static <T> List<T> limited(TypedQuery<T> q, Long branchId) {
        if (branchId != null) {
            q.setParameter("branchId", branchId);
        }
        return q.setMaxResults(MAX_ROWS + 1).getResultList();
    }

    private static List<Column> cols(String... keyThenLabel) {
        List<Column> out = new ArrayList<>();
        for (int i = 0; i < keyThenLabel.length; i += 2) {
            out.add(new Column(keyThenLabel[i], keyThenLabel[i + 1]));
        }
        return out;
    }

    private static List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    private static String name(AppUser u) {
        return u == null ? null : u.getFullName();
    }
}
