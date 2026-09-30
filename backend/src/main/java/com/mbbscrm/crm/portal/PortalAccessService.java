package com.mbbscrm.crm.portal;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.portal.PortalAccountStudent.Relation;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;

import jakarta.validation.constraints.NotNull;

/**
 * Staff side of the portal (spec 4.10): giving a student or parent a login, re-issuing the activation code,
 * and switching a login off. Admins can do this for any student, counsellors for their own.
 *
 * The activation code is shown to the staff member once and never stored in readable form. Staff pass it on
 * by phone or WhatsApp; the family uses it to choose their own password.
 */
@Service
public class PortalAccessService {

    static final Duration CODE_VALIDITY = Duration.ofDays(7);
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PortalAccountRepository accounts;
    private final PortalAccountStudentRepository links;
    private final PortalSessionService sessions;
    private final StudentService students;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public PortalAccessService(PortalAccountRepository accounts, PortalAccountStudentRepository links,
                               PortalSessionService sessions, StudentService students,
                               PasswordEncoder passwordEncoder, AuditService audit) {
        this.accounts = accounts;
        this.links = links;
        this.sessions = sessions;
        this.students = students;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    public record GrantRequest(@NotNull Relation relation) {
    }

    public record AccessRow(Long accountId, String phone, String displayName, Relation relation, boolean activated,
                            boolean active, boolean codePending, Instant codeExpiresAt, Instant lastLoginAt) {
        static AccessRow of(PortalAccountStudent link) {
            PortalAccount a = link.getAccount();
            boolean pending = a.getActivationCodeHash() != null && a.getActivationExpiresAt() != null
                    && a.getActivationExpiresAt().isAfter(Instant.now());
            return new AccessRow(a.getId(), a.getPhone(), a.getDisplayName(), link.getRelation(), a.isActivated(),
                    a.isActive(), pending, a.getActivationExpiresAt(), a.getLastLoginAt());
        }
    }

    /** {@code activationCode} is null when the login already works and was simply linked to this student. */
    public record Granted(AccessRow access, String activationCode) {
    }

    @Transactional(readOnly = true)
    public List<AccessRow> forStudent(Long studentId) {
        student(studentId);
        return links.findByStudentIdOrderByIdAsc(studentId).stream().map(AccessRow::of).toList();
    }

    @Transactional
    public Granted grant(Long studentId, Relation relation) {
        Student s = student(studentId);
        CurrentUser me = CurrentUser.get();
        String phone = relation == Relation.STUDENT ? s.getPhone() : s.getParentPhone();
        if (phone == null || phone.isBlank()) {
            throw ApiException.badRequest("Add the parent's phone number to the student's profile first");
        }
        String name = relation == Relation.STUDENT ? s.getFullName()
                : s.getParentName() != null ? s.getParentName() : "Parent of " + s.getFullName();
        PortalAccount account = accounts.findByPhone(phone)
                .orElseGet(() -> accounts.save(new PortalAccount(phone, name, me.id())));
        PortalAccountStudent link = links.findByAccountIdAndStudentId(account.getId(), s.getId())
                .orElseGet(() -> links.save(new PortalAccountStudent(account, s, relation)));
        String code = null;
        if (!account.isActivated() || !account.isActive()) {
            code = issueCode(account);
        }
        audit.record(me.id(), "PORTAL_ACCESS_GRANTED", "STUDENT", s.getId(), relation + " login " + account.getId());
        return new Granted(AccessRow.of(link), code);
    }

    /** New activation code; the old password and every open session stop working. */
    @Transactional
    public Granted reset(Long studentId, Long accountId) {
        PortalAccountStudent link = link(studentId, accountId);
        String code = issueCode(link.getAccount());
        sessions.revokeAll(accountId);
        audit.record(CurrentUser.get().id(), "PORTAL_ACCESS_RESET", "STUDENT", studentId, "login " + accountId);
        return new Granted(AccessRow.of(link), code);
    }

    @Transactional
    public AccessRow setActive(Long studentId, Long accountId, boolean active) {
        PortalAccountStudent link = link(studentId, accountId);
        link.getAccount().setActive(active);
        if (!active) {
            sessions.revokeAll(accountId);
        }
        audit.record(CurrentUser.get().id(), active ? "PORTAL_ACCESS_ENABLED" : "PORTAL_ACCESS_DISABLED", "STUDENT",
                studentId, "login " + accountId);
        return AccessRow.of(link);
    }

    private String issueCode(PortalAccount account) {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        account.issueActivation(passwordEncoder.encode(code.toString()), Instant.now().plus(CODE_VALIDITY));
        return code.toString();
    }

    static String normalizeCode(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private PortalAccountStudent link(Long studentId, Long accountId) {
        student(studentId);
        return links.findByAccountIdAndStudentId(accountId, studentId)
                .orElseThrow(() -> ApiException.notFound("Portal login"));
    }

    private Student student(Long studentId) {
        return students.requireAccess(studentId, EnumSet.of(Role.SUPER_ADMIN), true);
    }
}
