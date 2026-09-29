package com.mbbscrm.crm.grievance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
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
import com.mbbscrm.crm.helpdesk.Channel;
import com.mbbscrm.crm.helpdesk.Ticket;
import com.mbbscrm.crm.helpdesk.TicketRepository;
import com.mbbscrm.crm.helpdesk.TicketStatus;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Grievance register (spec 4.28). Any staff member can log a grievance; only the grievance officer and
 * admins can see and act on the register. Every step is appended to an audit trail that cannot be edited.
 */
@Service
public class GrievanceService {

    static final Set<Role> MANAGERS = EnumSet.of(Role.SUPER_ADMIN, Role.GRIEVANCE_OFFICER);
    static final int DEFAULT_TARGET_DAYS = 15;

    private final GrievanceRepository grievances;
    private final GrievanceActionRepository actions;
    private final StudentRepository students;
    private final TicketRepository tickets;
    private final AppUserRepository users;
    private final AlertService alerts;
    private final AuditService audit;
    private final EntityManager em;

    public GrievanceService(GrievanceRepository grievances, GrievanceActionRepository actions,
                            StudentRepository students, TicketRepository tickets, AppUserRepository users,
                            AlertService alerts, AuditService audit, EntityManager em) {
        this.grievances = grievances;
        this.actions = actions;
        this.students = students;
        this.tickets = tickets;
        this.users = users;
        this.alerts = alerts;
        this.audit = audit;
        this.em = em;
    }

    public record CreateRequest(Long studentId, Long ticketId, @Size(max = 120) String complainantName,
                                @Size(max = 20) String complainantPhone, @NotNull GrievanceCategory category,
                                @NotBlank @Size(max = 4000) String description,
                                @DecimalMin("0") BigDecimal amountInDispute, @NotNull Channel receivedVia,
                                Instant receivedAt) {
    }

    public record ActionRequest(@NotNull GrievanceActionType type, @Size(max = 4000) String details,
                                GrievanceStatus status, Long assignToId, LocalDate targetResolutionDate) {
    }

    public record ActionView(Long id, UserRef actor, GrievanceActionType type, String details, Instant createdAt) {
        static ActionView of(GrievanceAction a) {
            return new ActionView(a.getId(), UserRef.of(a.getActor()), a.getActionType(), a.getDetails(),
                    a.getCreatedAt());
        }
    }

    public record GrievanceView(Long id, String referenceNo, Long studentId, String studentName, Long ticketId,
                                String complainantName, String complainantPhone, GrievanceCategory category,
                                String description, BigDecimal amountInDispute, Channel receivedVia,
                                Instant receivedAt, GrievanceStatus status, UserRef assignedOfficer,
                                LocalDate targetResolutionDate, boolean overdue, String resolution,
                                Instant resolvedAt, UserRef createdBy, Instant createdAt, List<ActionView> trail) {
        static GrievanceView of(Grievance g, List<ActionView> trail) {
            return new GrievanceView(g.getId(), g.getReferenceNo(), g.getStudent() == null ? null : g.getStudent().getId(),
                    g.getStudent() == null ? null : g.getStudent().getFullName(), g.getTicketId(),
                    g.getComplainantName(), g.getComplainantPhone(), g.getCategory(), g.getDescription(),
                    g.getAmountInDispute(), g.getReceivedVia(), g.getReceivedAt(), g.getStatus(),
                    UserRef.of(g.getAssignedOfficer()), g.getTargetResolutionDate(),
                    g.isOpen() && g.getTargetResolutionDate().isBefore(LocalDate.now()), g.getResolution(),
                    g.getResolvedAt(), UserRef.of(g.getCreatedBy()), g.getCreatedAt(), trail);
        }
    }

    @Transactional
    public GrievanceView create(CreateRequest req) {
        CurrentUser me = CurrentUser.get();
        Grievance g = new Grievance();
        Student s = null;
        if (req.ticketId() != null) {
            Ticket t = tickets.findById(req.ticketId()).orElseThrow(() -> ApiException.badRequest("Ticket not found"));
            g.setTicketId(t.getId());
            s = t.getStudent();
        }
        if (req.studentId() != null) {
            s = students.findById(req.studentId()).orElseThrow(() -> ApiException.badRequest("Student not found"));
        }
        g.setStudent(s);
        String name = req.complainantName() != null && !req.complainantName().isBlank() ? req.complainantName().trim()
                : s != null ? s.getFullName() : null;
        if (name == null) {
            throw ApiException.badRequest("Enter the complainant's name or link a student");
        }
        g.setComplainantName(name);
        g.setComplainantPhone(req.complainantPhone() != null && !req.complainantPhone().isBlank()
                ? req.complainantPhone().trim() : s != null ? s.getPhone() : null);
        g.setCategory(req.category());
        g.setDescription(req.description().trim());
        g.setAmountInDispute(req.amountInDispute());
        g.setReceivedVia(req.receivedVia());
        g.setReceivedAt(req.receivedAt() != null ? req.receivedAt() : Instant.now());
        g.setTargetResolutionDate(LocalDate.now().plusDays(DEFAULT_TARGET_DAYS));
        g.setAssignedOfficer(defaultOfficer());
        g.setCreatedBy(users.getReferenceById(me.id()));
        g.setCreatedAt(Instant.now());
        g.setReferenceNo(nextReference());
        grievances.save(g);
        trail(g, me, GrievanceActionType.CREATED, g.getCategory() + " via " + g.getReceivedVia()
                + (g.getAmountInDispute() == null ? "" : ", amount in dispute Rs " + g.getAmountInDispute()));

        if (g.getTicketId() != null) {
            tickets.findById(g.getTicketId()).ifPresent(t -> {
                t.setStatus(TicketStatus.CLOSED);
                t.setResolution("Moved to grievance register as " + g.getReferenceNo());
                t.setResolvedAt(Instant.now());
            });
        }
        audit.record(me.id(), "GRIEVANCE_CREATED", "GRIEVANCE", g.getId(), g.getReferenceNo());
        String link = "/grievances/" + g.getId();
        Long studentId = s == null ? null : s.getId();
        if (g.getAssignedOfficer() != null) {
            alerts.notifyUser(g.getAssignedOfficer().getId(), studentId, "GRIEVANCE_NEW", Priority.URGENT,
                    "New grievance " + g.getReferenceNo() + ": " + g.getCategory(), g.getComplainantName(), link,
                    "GRV:" + g.getId() + ":NEW");
        }
        return get(g.getId(), true);
    }

    @Transactional(readOnly = true)
    public List<GrievanceView> list(boolean openOnly) {
        CurrentUser me = CurrentUser.get();
        List<Grievance> list = MANAGERS.contains(me.role())
                ? grievances.findByStatuses(openOnly ? EnumSet.of(GrievanceStatus.OPEN, GrievanceStatus.UNDER_REVIEW,
                        GrievanceStatus.ESCALATED) : EnumSet.allOf(GrievanceStatus.class))
                : grievances.findByCreatedByIdOrderByCreatedAtDesc(me.id());
        return list.stream().map(g -> GrievanceView.of(g, List.of())).toList();
    }

    @Transactional(readOnly = true)
    public GrievanceView get(Long id) {
        return get(id, false);
    }

    private GrievanceView get(Long id, boolean skipCheck) {
        Grievance g = grievances.findById(id).orElseThrow(() -> ApiException.notFound("Grievance"));
        CurrentUser me = CurrentUser.get();
        if (!skipCheck && !MANAGERS.contains(me.role())
                && (g.getCreatedBy() == null || !g.getCreatedBy().getId().equals(me.id()))) {
            throw ApiException.notFound("Grievance");
        }
        return GrievanceView.of(g, actions.findByGrievanceIdOrderByCreatedAtAsc(id).stream().map(ActionView::of).toList());
    }

    /** Every change to a grievance is an action, so the trail is the complete history. */
    @Transactional
    public GrievanceView act(Long id, ActionRequest req) {
        CurrentUser me = CurrentUser.get();
        if (!MANAGERS.contains(me.role())) {
            throw ApiException.forbidden("Only the grievance officer or an admin can act on grievances");
        }
        Grievance g = grievances.findById(id).orElseThrow(() -> ApiException.notFound("Grievance"));
        if (!g.isOpen() && req.type() != GrievanceActionType.NOTE) {
            throw ApiException.badRequest("This grievance is closed; only notes can be added");
        }
        String details = req.details() == null || req.details().isBlank() ? null : req.details().trim();
        switch (req.type()) {
            case NOTE, CONTACTED -> {
                if (details == null) {
                    throw ApiException.badRequest("Write what happened");
                }
                trail(g, me, req.type(), details);
            }
            case ASSIGNED -> {
                AppUser officer = users.findById(req.assignToId() == null ? -1 : req.assignToId())
                        .filter(AppUser::isActive).filter(u -> MANAGERS.contains(u.getRole()))
                        .orElseThrow(() -> ApiException.badRequest("Assign to a grievance officer or admin"));
                g.setAssignedOfficer(officer);
                trail(g, me, req.type(), "Assigned to " + officer.getFullName() + (details == null ? "" : ": " + details));
                alerts.notifyUser(officer.getId(), studentId(g), "GRIEVANCE_ASSIGNED", Priority.URGENT,
                        "Grievance " + g.getReferenceNo() + " assigned to you", null, "/grievances/" + g.getId(),
                        "GRV:" + g.getId() + ":ASSIGNED:" + officer.getId());
            }
            case STATUS_CHANGE -> {
                if (req.status() == null || req.status() == GrievanceStatus.RESOLVED
                        || req.status() == GrievanceStatus.ESCALATED) {
                    throw ApiException.badRequest("Use the resolve or escalate action for that");
                }
                GrievanceStatus before = g.getStatus();
                g.setStatus(req.status());
                if (req.targetResolutionDate() != null) {
                    g.setTargetResolutionDate(req.targetResolutionDate());
                }
                trail(g, me, req.type(), before + " -> " + g.getStatus()
                        + (req.targetResolutionDate() == null ? "" : ", target " + req.targetResolutionDate())
                        + (details == null ? "" : ": " + details));
            }
            case ESCALATED -> {
                if (details == null) {
                    throw ApiException.badRequest("Explain why this is being escalated");
                }
                g.setStatus(GrievanceStatus.ESCALATED);
                trail(g, me, req.type(), details);
                escalateToOwners(g, details);
            }
            case RESOLVED -> {
                if (details == null) {
                    throw ApiException.badRequest("Record the resolution");
                }
                g.setStatus(GrievanceStatus.RESOLVED);
                g.setResolution(details);
                g.setResolvedAt(Instant.now());
                trail(g, me, req.type(), details);
            }
            case CREATED -> throw ApiException.badRequest("Not allowed");
        }
        audit.record(me.id(), "GRIEVANCE_" + req.type(), "GRIEVANCE", g.getId(), g.getReferenceNo());
        return get(g.getId(), true);
    }

    void escalateToOwners(Grievance g, String why) {
        for (AppUser owner : users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN))) {
            alerts.notifyUser(owner.getId(), studentId(g), "GRIEVANCE_ESCALATED", Priority.URGENT,
                    "Grievance " + g.getReferenceNo() + " escalated", why, "/grievances/" + g.getId(),
                    "GRV:" + g.getId() + ":ESC:" + g.getStatus() + ":" + LocalDate.now());
        }
    }

    void trail(Grievance g, CurrentUser me, GrievanceActionType type, String details) {
        actions.save(new GrievanceAction(g.getId(), me == null ? null : users.getReferenceById(me.id()), type, details));
    }

    private AppUser defaultOfficer() {
        List<AppUser> officers = users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.GRIEVANCE_OFFICER));
        if (!officers.isEmpty()) {
            return officers.get(0);
        }
        List<AppUser> admins = users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN));
        return admins.isEmpty() ? null : admins.get(0);
    }

    private String nextReference() {
        Number n = (Number) em.createNativeQuery("select nextval('grievance_seq')").getSingleResult();
        return "GRV-" + Year.now().getValue() + "-" + String.format("%04d", n.longValue());
    }

    private static Long studentId(Grievance g) {
        return g.getStudent() == null ? null : g.getStudent().getId();
    }
}
