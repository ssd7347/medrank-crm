package com.mbbscrm.crm.agreement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.agreement.AgreementTemplate.Kind;
import com.mbbscrm.crm.agreement.StudentAgreement.SignMethod;
import com.mbbscrm.crm.agreement.StudentAgreement.Status;
import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Service agreements and data-consent forms (spec 4.20).
 *
 * Aadhaar e-Sign needs a licensed e-Sign provider, which is not connected. Until then a family can accept
 * an agreement inside their own portal login by typing their name (recorded with time, login and address),
 * or staff can record that a signed paper copy is on file. Both are stored with a fingerprint of the exact
 * text. Neither is presented as an Aadhaar e-signature.
 */
@Service
public class AgreementService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AgreementTemplateRepository templates;
    private final StudentAgreementRepository agreements;
    private final StudentService students;
    private final AlertService alerts;
    private final AuditService audit;
    private final String orgName;

    public AgreementService(AgreementTemplateRepository templates, StudentAgreementRepository agreements,
                            StudentService students, AlertService alerts, AuditService audit,
                            @Value("${app.org-name}") String orgName) {
        this.templates = templates;
        this.agreements = agreements;
        this.students = students;
        this.alerts = alerts;
        this.audit = audit;
        this.orgName = orgName;
    }

    // ------------------------------------------------------------------ DTOs

    public record TemplateRequest(@NotNull Kind kind, @NotBlank @Size(max = 200) String title,
                                  @NotBlank @Size(max = 20000) String body, boolean active) {
    }

    public record TemplateView(Long id, Kind kind, String title, String body, boolean active, Instant updatedAt) {
        static TemplateView of(AgreementTemplate t) {
            return new TemplateView(t.getId(), t.getKind(), t.getTitle(), t.getBody(), t.isActive(), t.getUpdatedAt());
        }
    }

    /** {@code body} is left out of lists and included when one agreement is opened. */
    public record AgreementView(Long id, Long studentId, String studentName, Kind kind, String title, String body,
                                Status status, SignMethod signMethod, String signerName, String signerRelation,
                                Instant signedAt, Instant issuedAt, String fingerprint, String orgName) {
    }

    public record PaperRequest(
            @NotBlank @Size(max = 120) String signerName,
            @NotBlank @Pattern(regexp = "STUDENT|PARENT|GUARDIAN", message = "must be STUDENT, PARENT or GUARDIAN")
            String signerRelation) {
    }

    // ------------------------------------------------------------------ templates

    @Transactional(readOnly = true)
    public List<TemplateView> templates() {
        return templates.findAllByOrderByKindAscTitleAsc().stream().map(TemplateView::of).toList();
    }

    @Transactional
    public TemplateView saveTemplate(Long id, TemplateRequest req) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can change agreement wording");
        }
        AgreementTemplate t = id == null ? new AgreementTemplate()
                : templates.findById(id).orElseThrow(() -> ApiException.notFound("Template"));
        t.setKind(req.kind());
        t.setTitle(req.title().trim());
        t.setBody(req.body().strip());
        t.setActive(req.active());
        t.setUpdatedBy(me.id());
        templates.save(t);
        audit.record(me.id(), id == null ? "AGREEMENT_TEMPLATE_CREATED" : "AGREEMENT_TEMPLATE_UPDATED",
                "AGREEMENT_TEMPLATE", t.getId(), t.getTitle());
        return TemplateView.of(t);
    }

    // ------------------------------------------------------------------ staff side

    @Transactional(readOnly = true)
    public List<AgreementView> forStudent(Long studentId) {
        students.requireReadable(studentId);
        return agreements.findByStudentIdOrderByIssuedAtDesc(studentId).stream().map(a -> view(a, false)).toList();
    }

    @Transactional(readOnly = true)
    public AgreementView get(Long id) {
        StudentAgreement a = agreements.findById(id).orElseThrow(() -> ApiException.notFound("Agreement"));
        students.requireReadable(a.getStudent().getId());
        return view(a, true);
    }

    /** Fills the template for this student and puts it in front of the family to sign. */
    @Transactional
    public AgreementView issue(Long studentId, Long templateId) {
        Student s = students.requireWritable(studentId);
        AgreementTemplate t = templates.findById(templateId).filter(AgreementTemplate::isActive)
                .orElseThrow(() -> ApiException.badRequest("That agreement template is not available"));
        boolean open = agreements.findByStudentIdOrderByIssuedAtDesc(studentId).stream()
                .anyMatch(a -> a.getStatus() == Status.PENDING && a.getKind() == t.getKind());
        if (open) {
            throw ApiException.conflict("This student already has one of these waiting to be signed");
        }
        String body = fill(t.getBody(), s);
        CurrentUser me = CurrentUser.get();
        StudentAgreement a = agreements.save(new StudentAgreement(s, t, body, sha256(body), me.id()));
        audit.record(me.id(), "AGREEMENT_ISSUED", "STUDENT_AGREEMENT", a.getId(), t.getKind().name());
        return view(a, true);
    }

    @Transactional
    public AgreementView recordPaper(Long id, PaperRequest req) {
        StudentAgreement a = pending(id);
        students.requireWritable(a.getStudent().getId());
        CurrentUser me = CurrentUser.get();
        a.sign(SignMethod.PAPER, req.signerName().trim(), req.signerRelation(), null, null, me.id());
        audit.record(me.id(), "AGREEMENT_SIGNED", "STUDENT_AGREEMENT", a.getId(), "paper, " + req.signerName().trim());
        return view(a, true);
    }

    @Transactional
    public AgreementView cancel(Long id) {
        StudentAgreement a = pending(id);
        students.requireWritable(a.getStudent().getId());
        a.cancel();
        audit.record(CurrentUser.get().id(), "AGREEMENT_CANCELLED", "STUDENT_AGREEMENT", a.getId(), null);
        return view(a, false);
    }

    // ------------------------------------------------------------------ portal side (caller checked the link)

    @Transactional(readOnly = true)
    public List<AgreementView> forPortal(Long studentId) {
        return agreements.findByStudentIdOrderByIssuedAtDesc(studentId).stream()
                .filter(a -> a.getStatus() != Status.CANCELLED).map(a -> view(a, true)).toList();
    }

    @Transactional
    public AgreementView signFromPortal(Student student, Long agreementId, String typedName, String relation,
                                        String ip, Long portalAccountId) {
        StudentAgreement a = pending(agreementId);
        if (!a.getStudent().getId().equals(student.getId())) {
            throw ApiException.notFound("Agreement");
        }
        if (!sha256(a.getBody()).equals(a.getBodySha256())) {
            throw ApiException.conflict("This agreement could not be verified. Please ask your counsellor to issue it again.");
        }
        a.sign(SignMethod.PORTAL_ACCEPTANCE, typedName.trim(), relation, ip, portalAccountId, null);
        audit.record(null, "AGREEMENT_SIGNED", "STUDENT_AGREEMENT", a.getId(), "portal login " + portalAccountId);
        alerts.notifyStaffFor(student, "AGREEMENT_SIGNED", Priority.NORMAL,
                student.getFullName() + " accepted: " + a.getTitle(), "Accepted in the portal by " + typedName.trim()
                        + ".", "/students/" + student.getId() + "?tab=agreements", "AGREEMENT:" + a.getId());
        return view(a, true);
    }

    // ------------------------------------------------------------------ helpers

    private StudentAgreement pending(Long id) {
        StudentAgreement a = agreements.findById(id).orElseThrow(() -> ApiException.notFound("Agreement"));
        if (a.getStatus() != Status.PENDING) {
            throw ApiException.conflict("This agreement is already " + a.getStatus().name().toLowerCase()
                    + " and cannot be changed");
        }
        return a;
    }

    private String fill(String body, Student s) {
        return body.replace("{{orgName}}", orgName)
                .replace("{{studentName}}", s.getFullName())
                .replace("{{parentName}}", s.getParentName() == null ? "the parent or guardian" : s.getParentName())
                .replace("{{neetYear}}", s.getNeetYear() == null ? String.valueOf(LocalDate.now(IST).getYear())
                        : String.valueOf(s.getNeetYear()))
                .replace("{{date}}", LocalDate.now(IST).toString());
    }

    private AgreementView view(StudentAgreement a, boolean withBody) {
        return new AgreementView(a.getId(), a.getStudent().getId(), a.getStudent().getFullName(), a.getKind(),
                a.getTitle(), withBody ? a.getBody() : null, a.getStatus(), a.getSignMethod(), a.getSignerName(),
                a.getSignerRelation(), a.getSignedAt(), a.getIssuedAt(), a.getBodySha256().substring(0, 16), orgName);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
