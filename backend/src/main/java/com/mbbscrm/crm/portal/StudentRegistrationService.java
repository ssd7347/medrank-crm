package com.mbbscrm.crm.portal;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.DomicileStatus;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.common.Nationality;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.portal.PortalAccountStudent.Relation;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The "Register" option on the login page. Only students register themselves; staff accounts are created by
 * the admin. Registering creates the student's ID (profile + login) and tells the admin, who assigns a
 * counsellor. A student only ever sees their own record, and still has to prove the mobile number with a
 * one-time code to sign in.
 */
@Service
public class StudentRegistrationService {

    private final StudentRepository students;
    private final LeadRepository leads;
    private final PortalAccountRepository accounts;
    private final PortalAccountStudentRepository links;
    private final AppUserRepository users;
    private final AlertService alerts;
    private final AuditService audit;

    public StudentRegistrationService(StudentRepository students, LeadRepository leads,
                                      PortalAccountRepository accounts, PortalAccountStudentRepository links,
                                      AppUserRepository users, AlertService alerts, AuditService audit) {
        this.students = students;
        this.leads = leads;
        this.accounts = accounts;
        this.links = links;
        this.users = users;
        this.alerts = alerts;
        this.audit = audit;
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank String phone,
            @Email @Size(max = 160) String email,
            @NotNull Category category,
            @NotBlank @Size(max = 40) String homeState,
            @Min(-180) @Max(720) Integer neetScore,
            @Min(1) @Max(3_000_000) Integer neetAir,
            @Size(max = 120) String parentName,
            @Pattern(regexp = "^([0-9+ -]{10,20})?$", message = "invalid phone") String parentPhone,
            Language language,
            /** Hidden from people; only bots fill it in. */
            @Size(max = 200) String website) {
    }

    /** {@code studentId} is the new ID, e.g. "STU-0012". */
    public record Registered(String studentId, String fullName) {
    }

    public static String studentCode(Long id) {
        return String.format("STU-%04d", id);
    }

    @Transactional
    public Registered register(RegisterRequest req) {
        String phone = Phones.normalize(req.phone().trim());
        if (phone == null || !phone.matches("[0-9]{10}")) {
            throw ApiException.badRequest("Enter your 10-digit mobile number");
        }
        if (users.findByPhone(phone).isPresent() || accounts.findByPhone(phone).isPresent()) {
            throw ApiException.conflict("This mobile number already has an ID. Use Login instead.");
        }
        String parentPhone = req.parentPhone() == null || req.parentPhone().isBlank() ? null
                : Phones.normalize(req.parentPhone());
        String name = req.fullName().trim();

        // If the consultancy already has this student on file under the same number, the login is attached
        // to that record rather than creating a second one. Signing in still needs the code sent to the number.
        List<Student> existing = students.findByPhone(phone);
        Student student;
        if (existing.size() == 1) {
            student = existing.get(0);
        } else {
            student = new Student();
            student.setFullName(name);
            student.setPhone(phone);
            student.setEmail(req.email() == null || req.email().isBlank() ? null : req.email().trim());
            student.setParentName(req.parentName() == null || req.parentName().isBlank() ? null : req.parentName().trim());
            student.setParentPhone(parentPhone);
            student.setCategory(req.category());
            student.setHomeState(req.homeState().trim());
            student.setDomicileStatus(DomicileStatus.UNKNOWN);
            student.setNationality(Nationality.INDIAN);
            student.setNeetScore(req.neetScore());
            student.setNeetAir(req.neetAir());
            student.setLanguagePreference(req.language() == null ? Language.ENGLISH : req.language());
            students.save(student);

            // Show up in the lead pipeline too, so someone follows up.
            Lead lead = leads.findByAnyPhone(phone).stream().filter(l -> l.getStudent() == null).findFirst()
                    .orElseGet(() -> {
                        Lead l = new Lead();
                        l.setFullName(name);
                        l.setPhone(phone);
                        l.setSource(LeadSource.SELF_REGISTERED);
                        l.setNotes("Registered on the website.");
                        return l;
                    });
            lead.setStudent(student);
            lead.setNeetScore(req.neetScore());
            lead.setNeetAir(req.neetAir());
            lead.setCategory(req.category());
            lead.setHomeState(req.homeState().trim());
            lead.setLanguagePreference(student.getLanguagePreference());
            if (lead.getStatus() == LeadStatus.NEW) {
                lead.setStatus(LeadStatus.QUALIFIED);
            }
            leads.save(lead);
            if (lead.getAssignedCounsellor() != null) {
                student.setAssignedCounsellor(lead.getAssignedCounsellor());
            }
            student.setBranch(lead.getBranch());
        }

        PortalAccount account = accounts.save(new PortalAccount(phone, student.getFullName(), null));
        links.save(new PortalAccountStudent(account, student, Relation.STUDENT));
        audit.record(null, "STUDENT_REGISTERED", "STUDENT", student.getId(), "portal login " + account.getId());

        AppUser counsellor = student.getAssignedCounsellor();
        List<Long> recipients = counsellor != null && counsellor.isActive() ? List.of(counsellor.getId())
                : users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN)).stream()
                        .map(AppUser::getId).toList();
        for (Long recipient : recipients) {
            alerts.notifyUser(recipient, student.getId(), "STUDENT_REGISTERED", Priority.NORMAL,
                    "New student registered: " + student.getFullName(),
                    counsellor == null ? "No counsellor is assigned yet." : "They created their own login.",
                    "/students/" + student.getId(), "STUDENT_REGISTERED:" + student.getId());
        }
        return new Registered(studentCode(student.getId()), student.getFullName());
    }
}
