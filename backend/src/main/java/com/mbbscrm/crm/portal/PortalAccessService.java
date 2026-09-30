package com.mbbscrm.crm.portal;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.portal.PortalAccountStudent.Relation;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.AppUserRepository;

import jakarta.validation.constraints.NotNull;

/**
 * Staff side of the portal (spec 4.10): giving a student or parent a login and switching it off. The admin
 * can do this for any student, counsellors for their own. The family then signs in on the common login page
 * with that mobile number and a one-time code; there is nothing to hand over.
 */
@Service
public class PortalAccessService {

    private final PortalAccountRepository accounts;
    private final PortalAccountStudentRepository links;
    private final PortalSessionService sessions;
    private final StudentService students;
    private final AppUserRepository users;
    private final AuditService audit;

    public PortalAccessService(PortalAccountRepository accounts, PortalAccountStudentRepository links,
                               PortalSessionService sessions, StudentService students, AppUserRepository users,
                               AuditService audit) {
        this.accounts = accounts;
        this.links = links;
        this.sessions = sessions;
        this.students = students;
        this.users = users;
        this.audit = audit;
    }

    public record GrantRequest(@NotNull Relation relation) {
    }

    public record AccessRow(Long accountId, String phone, String displayName, Relation relation, boolean active,
                            Instant lastLoginAt, boolean selfRegistered) {
        static AccessRow of(PortalAccountStudent link) {
            PortalAccount a = link.getAccount();
            return new AccessRow(a.getId(), a.getPhone(), a.getDisplayName(), link.getRelation(), a.isActive(),
                    a.getLastLoginAt(), a.getCreatedBy() == null);
        }
    }

    @Transactional(readOnly = true)
    public List<AccessRow> forStudent(Long studentId) {
        student(studentId);
        return links.findByStudentIdOrderByIdAsc(studentId).stream().map(AccessRow::of).toList();
    }

    @Transactional
    public AccessRow grant(Long studentId, Relation relation) {
        Student s = student(studentId);
        CurrentUser me = CurrentUser.get();
        String phone = relation == Relation.STUDENT ? s.getPhone() : s.getParentPhone();
        if (phone == null || phone.isBlank()) {
            throw ApiException.badRequest("Add the parent's phone number to the student's profile first");
        }
        if (users.findByPhone(phone).isPresent()) {
            throw ApiException.conflict("This mobile number belongs to a staff member, so it cannot also be a "
                    + "student or parent login");
        }
        String name = relation == Relation.STUDENT ? s.getFullName()
                : s.getParentName() != null ? s.getParentName() : "Parent of " + s.getFullName();
        PortalAccount account = accounts.findByPhone(phone)
                .orElseGet(() -> accounts.save(new PortalAccount(phone, name, me.id())));
        account.setActive(true);
        PortalAccountStudent link = links.findByAccountIdAndStudentId(account.getId(), s.getId())
                .orElseGet(() -> links.save(new PortalAccountStudent(account, s, relation)));
        audit.record(me.id(), "PORTAL_ACCESS_GRANTED", "STUDENT", s.getId(), relation + " login " + account.getId());
        return AccessRow.of(link);
    }

    @Transactional
    public AccessRow setActive(Long studentId, Long accountId, boolean active) {
        student(studentId);
        PortalAccountStudent link = links.findByAccountIdAndStudentId(accountId, studentId)
                .orElseThrow(() -> ApiException.notFound("Portal login"));
        link.getAccount().setActive(active);
        if (!active) {
            sessions.revokeAll(accountId);
        }
        audit.record(CurrentUser.get().id(), active ? "PORTAL_ACCESS_ENABLED" : "PORTAL_ACCESS_DISABLED", "STUDENT",
                studentId, "login " + accountId);
        return AccessRow.of(link);
    }

    private Student student(Long studentId) {
        return students.requireAccess(studentId, EnumSet.of(Role.SUPER_ADMIN), true);
    }
}
