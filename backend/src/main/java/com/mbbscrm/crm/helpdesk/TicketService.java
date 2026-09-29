package com.mbbscrm.crm.helpdesk;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Helpdesk tickets (spec 4.14). Any staff member can log one. Admins and the grievance officer see all
 * tickets; everyone else sees tickets assigned to or raised by them, and counsellors also see tickets about
 * their own students.
 */
@Service
public class TicketService {

    static final Set<Role> SEE_ALL = EnumSet.of(Role.SUPER_ADMIN, Role.GRIEVANCE_OFFICER);
    static final Set<TicketStatus> OPEN = EnumSet.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS,
            TicketStatus.WAITING_ON_STUDENT);

    private final TicketRepository tickets;
    private final TicketCommentRepository comments;
    private final StudentRepository students;
    private final LeadRepository leads;
    private final AppUserRepository users;
    private final AlertService alerts;
    private final AuditService audit;

    public TicketService(TicketRepository tickets, TicketCommentRepository comments, StudentRepository students,
                         LeadRepository leads, AppUserRepository users, AlertService alerts, AuditService audit) {
        this.tickets = tickets;
        this.comments = comments;
        this.students = students;
        this.leads = leads;
        this.users = users;
        this.alerts = alerts;
        this.audit = audit;
    }

    public record CreateRequest(Long studentId, Long leadId, @Size(max = 120) String raisedByName,
                                @NotNull Channel raisedVia, @NotBlank @Size(max = 200) String subject,
                                @Size(max = 4000) String description, @NotNull TicketCategory category,
                                @NotNull TicketPriority priority, boolean deadlineLinked, Long assignedToId) {
    }

    public record UpdateRequest(@NotNull TicketStatus status, @NotNull TicketPriority priority, Long assignedToId,
                                @Size(max = 2000) String resolution) {
    }

    public record CommentRequest(@NotBlank @Size(max = 4000) String body) {
    }

    public record CommentView(Long id, UserRef author, String body, Instant createdAt) {
        static CommentView of(TicketComment c) {
            return new CommentView(c.getId(), UserRef.of(c.getAuthor()), c.getBody(), c.getCreatedAt());
        }
    }

    public record TicketView(Long id, Long studentId, String studentName, Long leadId, String raisedByName,
                             Channel raisedVia, String subject, String description, TicketCategory category,
                             TicketPriority priority, boolean deadlineLinked, TicketStatus status, UserRef assignedTo,
                             Instant dueAt, boolean overdue, String resolution, Instant resolvedAt, UserRef createdBy,
                             Instant createdAt, List<CommentView> comments) {
        static TicketView of(Ticket t, List<CommentView> comments) {
            return new TicketView(t.getId(), t.getStudent() == null ? null : t.getStudent().getId(),
                    t.getStudent() == null ? null : t.getStudent().getFullName(),
                    t.getLead() == null ? null : t.getLead().getId(), t.getRaisedByName(), t.getRaisedVia(),
                    t.getSubject(), t.getDescription(), t.getCategory(), t.getPriority(), t.isDeadlineLinked(),
                    t.getStatus(), UserRef.of(t.getAssignedTo()), t.getDueAt(),
                    t.isOpen() && t.getDueAt().isBefore(Instant.now()), t.getResolution(), t.getResolvedAt(),
                    UserRef.of(t.getCreatedBy()), t.getCreatedAt(), comments);
        }
    }

    @Transactional(readOnly = true)
    public List<TicketView> list(boolean openOnly, boolean mineOnly) {
        CurrentUser me = CurrentUser.get();
        Set<TicketStatus> statuses = openOnly ? OPEN : EnumSet.allOf(TicketStatus.class);
        return tickets.findByStatuses(statuses).stream()
                .filter(t -> canSee(t, me))
                .filter(t -> !mineOnly || (t.getAssignedTo() != null && t.getAssignedTo().getId().equals(me.id())))
                .map(t -> TicketView.of(t, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public List<TicketView> forStudent(Long studentId) {
        CurrentUser me = CurrentUser.get();
        return tickets.findByStudentIdOrderByCreatedAtDesc(studentId).stream().filter(t -> canSee(t, me))
                .map(t -> TicketView.of(t, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public TicketView get(Long id) {
        Ticket t = visible(id);
        return TicketView.of(t, comments.findByTicketIdOrderByCreatedAtAsc(id).stream().map(CommentView::of).toList());
    }

    @Transactional
    public TicketView create(CreateRequest req) {
        CurrentUser me = CurrentUser.get();
        Ticket t = new Ticket();
        if (req.studentId() != null) {
            t.setStudent(students.findById(req.studentId()).orElseThrow(() -> ApiException.badRequest("Student not found")));
        }
        if (req.leadId() != null) {
            t.setLead(leads.findById(req.leadId()).orElseThrow(() -> ApiException.badRequest("Lead not found")));
        }
        if (t.getStudent() == null && t.getLead() == null && (req.raisedByName() == null || req.raisedByName().isBlank())) {
            throw ApiException.badRequest("Link a student or lead, or enter who raised it");
        }
        t.setRaisedByName(blankToNull(req.raisedByName()));
        t.setRaisedVia(req.raisedVia());
        t.setSubject(req.subject().trim());
        t.setDescription(blankToNull(req.description()));
        t.setCategory(req.category());
        TicketPriority priority = req.deadlineLinked() && req.priority().compareTo(TicketPriority.HIGH) < 0
                ? TicketPriority.HIGH : req.priority();
        t.setPriority(priority);
        t.setDeadlineLinked(req.deadlineLinked());
        t.setDueAt(Instant.now().plus(Ticket.slaFor(priority, req.deadlineLinked())));
        t.setCreatedBy(users.getReferenceById(me.id()));
        AppUser assignee = req.assignedToId() != null ? activeUser(req.assignedToId())
                : t.getStudent() != null && t.getStudent().getAssignedCounsellor() != null
                ? t.getStudent().getAssignedCounsellor() : users.findById(me.id()).orElseThrow();
        t.setAssignedTo(assignee);
        tickets.save(t);
        audit.record(me.id(), "TICKET_CREATED", "TICKET", t.getId(), priority + " " + t.getSubject());
        if (!assignee.getId().equals(me.id())) {
            alerts.notifyUser(assignee.getId(), t.getStudent() == null ? null : t.getStudent().getId(), "TICKET_ASSIGNED",
                    priority == TicketPriority.URGENT || t.isDeadlineLinked() ? Priority.URGENT : Priority.NORMAL,
                    "Ticket assigned: " + t.getSubject(), "Respond by " + com.mbbscrm.crm.counselling.AlertTexts
                            .when(t.getDueAt()) + ".", "/tickets/" + t.getId(), "TICKET:" + t.getId() + ":ASSIGNED");
        }
        return TicketView.of(t, List.of());
    }

    @Transactional
    public TicketView update(Long id, UpdateRequest req) {
        Ticket t = visible(id);
        CurrentUser me = CurrentUser.get();
        boolean isAssignee = t.getAssignedTo() != null && t.getAssignedTo().getId().equals(me.id());
        if (!SEE_ALL.contains(me.role()) && !isAssignee) {
            throw ApiException.forbidden("Only the assignee or an admin can update this ticket");
        }
        if ((req.status() == TicketStatus.RESOLVED || req.status() == TicketStatus.CLOSED)
                && (req.resolution() == null || req.resolution().isBlank()) && t.getResolution() == null) {
            throw ApiException.badRequest("Describe how it was resolved");
        }
        String before = t.getStatus() + "/" + t.getPriority();
        if (req.priority() != t.getPriority()) {
            t.setPriority(req.priority());
            t.setDueAt(t.getCreatedAt().plus(Ticket.slaFor(req.priority(), t.isDeadlineLinked())));
        }
        t.setStatus(req.status());
        if (!t.isOpen() && t.getResolvedAt() == null) {
            t.setResolvedAt(Instant.now());
        } else if (t.isOpen()) {
            t.setResolvedAt(null);
        }
        if (req.resolution() != null && !req.resolution().isBlank()) {
            t.setResolution(req.resolution().trim());
        }
        if (req.assignedToId() != null && (t.getAssignedTo() == null || !req.assignedToId().equals(t.getAssignedTo().getId()))) {
            AppUser a = activeUser(req.assignedToId());
            t.setAssignedTo(a);
            alerts.notifyUser(a.getId(), t.getStudent() == null ? null : t.getStudent().getId(), "TICKET_ASSIGNED",
                    Priority.NORMAL, "Ticket assigned: " + t.getSubject(), null, "/tickets/" + t.getId(),
                    "TICKET:" + t.getId() + ":ASSIGNED:" + a.getId());
        }
        audit.record(me.id(), "TICKET_UPDATED", "TICKET", t.getId(), before + " -> " + t.getStatus() + "/" + t.getPriority());
        return get(id);
    }

    @Transactional
    public TicketView comment(Long id, CommentRequest req) {
        Ticket t = visible(id);
        comments.save(new TicketComment(t.getId(), users.getReferenceById(CurrentUser.get().id()), req.body().trim()));
        if (t.getStatus() == TicketStatus.OPEN) {
            t.setStatus(TicketStatus.IN_PROGRESS);
        }
        return get(id);
    }

    Ticket visible(Long id) {
        Ticket t = tickets.findById(id).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (!canSee(t, CurrentUser.get())) {
            throw ApiException.notFound("Ticket");
        }
        return t;
    }

    static boolean canSee(Ticket t, CurrentUser me) {
        if (SEE_ALL.contains(me.role())) {
            return true;
        }
        if (t.getAssignedTo() != null && t.getAssignedTo().getId().equals(me.id())) {
            return true;
        }
        if (t.getCreatedBy() != null && t.getCreatedBy().getId().equals(me.id())) {
            return true;
        }
        Student s = t.getStudent();
        return me.role() == Role.COUNSELLOR && s != null && s.getAssignedCounsellor() != null
                && s.getAssignedCounsellor().getId().equals(me.id());
    }

    private AppUser activeUser(Long id) {
        return users.findById(id).filter(AppUser::isActive)
                .orElseThrow(() -> ApiException.badRequest("Assignee not found or inactive"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
