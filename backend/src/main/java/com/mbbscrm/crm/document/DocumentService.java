package com.mbbscrm.crm.document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.storage.FileStorage;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;

/**
 * Document checklist, uploads and verification (spec 4.7). Documentation staff and admins work on every
 * student; counsellors on their own students. Category/income certificates are sensitive personal data,
 * so every download is audited.
 */
@Service
public class DocumentService {

    static final Set<Role> FULL_ACCESS = EnumSet.of(Role.SUPER_ADMIN, Role.DOCUMENTATION_EXEC);
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final int EXPIRY_WARNING_DAYS = 30;

    private final DocumentTypeRepository types;
    private final StudentDocumentRepository docs;
    private final DocumentFileRepository files;
    private final StudentService students;
    private final AppUserRepository users;
    private final FileStorage storage;
    private final AuditService audit;

    public DocumentService(DocumentTypeRepository types, StudentDocumentRepository docs, DocumentFileRepository files,
                           StudentService students, AppUserRepository users, FileStorage storage, AuditService audit) {
        this.types = types;
        this.docs = docs;
        this.files = files;
        this.students = students;
        this.users = users;
        this.storage = storage;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ DTOs

    public record FileInfo(Long id, String originalName, String contentType, long sizeBytes, UserRef uploadedBy,
                           Instant uploadedAt, boolean fromPortal) {
        static FileInfo of(DocumentFile f) {
            return new FileInfo(f.getId(), f.getOriginalName(), f.getContentType(), f.getSizeBytes(),
                    UserRef.of(f.getUploadedBy()), f.getUploadedAt(), f.getPortalAccountId() != null);
        }
    }

    public record ChecklistItem(Long typeId, String code, String name, boolean required, boolean requiresExpiry,
                                Long documentId, DocumentStatus status, LocalDate validUntil, String notes,
                                UserRef verifiedBy, Instant verifiedAt, boolean expired, boolean expiringSoon,
                                List<FileInfo> files) {
    }

    public record Checklist(Long studentId, int requiredCount, int requiredDone, List<String> missingRequired,
                            List<ChecklistItem> items) {
    }

    public record UpdateRequest(DocumentStatus status, LocalDate validUntil, String notes) {
    }

    public record QueueRow(Long documentId, Long studentId, String studentName, UserRef counsellor, String typeName,
                           DocumentStatus status, LocalDate validUntil, Instant updatedAt) {
        static QueueRow of(StudentDocument d) {
            Student s = d.getStudent();
            return new QueueRow(d.getId(), s.getId(), s.getFullName(), UserRef.of(s.getAssignedCounsellor()),
                    d.getDocumentType().getName(), d.getStatus(), d.getValidUntil(), d.getUpdatedAt());
        }
    }

    public record Queue(List<QueueRow> awaitingVerification, List<QueueRow> expiringSoon) {
    }

    public record Download(String fileName, String contentType, byte[] content) {
    }

    // ------------------------------------------------------------------ checklist

    @Transactional(readOnly = true)
    public Checklist checklist(Long studentId) {
        Student s = students.requireAccess(studentId, FULL_ACCESS, true);
        return buildChecklist(s);
    }

    /** No access check: for callers that have already checked access themselves. */
    public Checklist buildChecklist(Student s) {
        Map<Long, StudentDocument> byType = docs.findByStudentId(s.getId()).stream()
                .collect(Collectors.toMap(d -> d.getDocumentType().getId(), d -> d));
        Map<Long, List<FileInfo>> filesByDoc = new HashMap<>();
        if (!byType.isEmpty()) {
            files.findByStudentDocumentIdInOrderByUploadedAtDesc(byType.values().stream().map(StudentDocument::getId)
                    .toList()).forEach(f -> filesByDoc.computeIfAbsent(f.getStudentDocument().getId(),
                    k -> new ArrayList<>()).add(FileInfo.of(f)));
        }
        LocalDate today = LocalDate.now();
        List<ChecklistItem> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int required = 0;
        int done = 0;
        for (DocumentType t : types.findByActiveTrueOrderBySortOrderAscNameAsc()) {
            boolean req = t.requiredFor(s);
            StudentDocument d = byType.get(t.getId());
            if (!req && t.getAppliesWhen() != AppliesWhen.OPTIONAL && d == null) {
                continue; // not relevant to this student and nothing recorded
            }
            DocumentStatus status = d == null ? DocumentStatus.NOT_COLLECTED : d.getStatus();
            LocalDate until = d == null ? null : d.getValidUntil();
            boolean expired = until != null && until.isBefore(today);
            boolean soon = until != null && !expired && until.isBefore(today.plusDays(EXPIRY_WARNING_DAYS));
            if (req) {
                required++;
                if ((status == DocumentStatus.VERIFIED || status == DocumentStatus.SUBMITTED) && !expired) {
                    done++;
                } else {
                    missing.add(t.getName());
                }
            }
            items.add(new ChecklistItem(t.getId(), t.getCode(), t.getName(), req, t.isRequiresExpiry(),
                    d == null ? null : d.getId(), status, until, d == null ? null : d.getNotes(),
                    d == null ? null : UserRef.of(d.getVerifiedBy()), d == null ? null : d.getVerifiedAt(), expired,
                    soon, d == null ? List.of() : filesByDoc.getOrDefault(d.getId(), List.of())));
        }
        return new Checklist(s.getId(), required, done, missing, items);
    }

    @Transactional
    public Checklist update(Long studentId, Long typeId, UpdateRequest req) {
        Student s = students.requireAccess(studentId, FULL_ACCESS, true);
        DocumentType t = types.findById(typeId).orElseThrow(() -> ApiException.notFound("Document type"));
        StudentDocument d = docs.findByStudentIdAndDocumentTypeId(s.getId(), t.getId())
                .orElseGet(() -> docs.save(new StudentDocument(s, t)));
        CurrentUser me = CurrentUser.get();
        DocumentStatus before = d.getStatus();
        if (req.status() != null && req.status() != before) {
            d.setStatus(req.status(), users.getReferenceById(me.id()));
        }
        if (t.isRequiresExpiry() || req.validUntil() != null) {
            d.setValidUntil(req.validUntil());
        }
        d.setNotes(req.notes() == null || req.notes().isBlank() ? null
                : req.notes().trim().substring(0, Math.min(req.notes().trim().length(), 500)));
        d.setUpdatedBy(users.getReferenceById(me.id()));
        audit.record(me.id(), "DOCUMENT_UPDATED", "STUDENT_DOCUMENT", d.getId(),
                t.getCode() + " " + before + " -> " + d.getStatus() + (d.getValidUntil() == null ? ""
                        : ", validUntil=" + d.getValidUntil()));
        return buildChecklist(s);
    }

    /** Stores a scan (PDF, JPEG or PNG, max 10 MB). Moves "not collected" / "rejected" items to "collected". */
    @Transactional
    public Checklist upload(Long studentId, Long typeId, MultipartFile file) {
        Student s = students.requireAccess(studentId, FULL_ACCESS, true);
        CurrentUser me = CurrentUser.get();
        store(s, typeId, file, users.getReferenceById(me.id()), me.id(), null);
        return buildChecklist(s);
    }

    /**
     * A scan sent by the student or parent through the portal. The caller has already checked that this
     * login belongs to the student; the file lands in the documentation team's verification queue.
     */
    @Transactional
    public Checklist uploadFromPortal(Student s, Long typeId, MultipartFile file, Long portalAccountId) {
        DocumentType t = types.findById(typeId).filter(DocumentType::isActive)
                .orElseThrow(() -> ApiException.notFound("Document type"));
        if (!t.requiredFor(s) && t.getAppliesWhen() != AppliesWhen.OPTIONAL) {
            throw ApiException.badRequest("This document is not on the checklist");
        }
        store(s, typeId, file, null, null, portalAccountId);
        return buildChecklist(s);
    }

    private void store(Student s, Long typeId, MultipartFile file, AppUser uploader, Long actorId,
                       Long portalAccountId) {
        DocumentType t = types.findById(typeId).orElseThrow(() -> ApiException.notFound("Document type"));
        byte[] bytes = readChecked(file);
        String contentType = sniff(bytes);
        if (contentType == null) {
            throw ApiException.badRequest("Only PDF, JPEG or PNG files are accepted");
        }
        StudentDocument d = docs.findByStudentIdAndDocumentTypeId(s.getId(), t.getId())
                .orElseGet(() -> docs.save(new StudentDocument(s, t)));
        String ext = switch (contentType) {
            case "application/pdf" -> "pdf";
            case "image/png" -> "png";
            default -> "jpg";
        };
        String key = "students/" + s.getId() + "/" + UUID.randomUUID() + "." + ext;
        storage.put(key, bytes, contentType);
        DocumentFile saved = new DocumentFile(d, key, safeName(file.getOriginalFilename(), ext), contentType,
                bytes.length, sha256(bytes), uploader);
        saved.setPortalAccountId(portalAccountId);
        files.save(saved);
        if (d.getStatus() == DocumentStatus.NOT_COLLECTED || d.getStatus() == DocumentStatus.REJECTED) {
            d.setStatus(DocumentStatus.COLLECTED, uploader);
        }
        audit.record(actorId, "DOCUMENT_UPLOADED", "STUDENT_DOCUMENT", d.getId(), t.getCode() + " " + bytes.length
                + " bytes" + (portalAccountId == null ? "" : " via portal login " + portalAccountId));
    }

    @Transactional(readOnly = true)
    public Download download(Long fileId) {
        DocumentFile f = files.findById(fileId).orElseThrow(() -> ApiException.notFound("File"));
        students.requireAccess(f.getStudentDocument().getStudent().getId(), FULL_ACCESS, true);
        byte[] content = storage.get(f.getStorageKey());
        audit.recordStandalone(CurrentUser.get().id(), "DOCUMENT_VIEWED", "DOCUMENT_FILE", f.getId(),
                f.getStudentDocument().getDocumentType().getCode());
        return new Download(f.getOriginalName(), f.getContentType(), content);
    }

    // ------------------------------------------------------------------ verification queue

    @Transactional(readOnly = true)
    public Queue queue() {
        CurrentUser me = CurrentUser.get();
        if (!FULL_ACCESS.contains(me.role()) && me.role() != Role.COUNSELLOR) {
            throw ApiException.forbidden("You do not have access to documents");
        }
        boolean mineOnly = me.role() == Role.COUNSELLOR;
        List<QueueRow> awaiting = docs.findByStatusOrderByUpdatedAtAsc(DocumentStatus.COLLECTED).stream()
                .filter(d -> !me.outsideBranch(d.getStudent().getBranch()))
                .filter(d -> !mineOnly || isMine(d.getStudent(), me)).map(QueueRow::of).toList();
        List<QueueRow> expiring = docs.findExpiringBy(LocalDate.now().plusDays(EXPIRY_WARNING_DAYS)).stream()
                .filter(d -> !me.outsideBranch(d.getStudent().getBranch()))
                .filter(d -> !mineOnly || isMine(d.getStudent(), me)).map(QueueRow::of).toList();
        return new Queue(awaiting, expiring);
    }

    private static boolean isMine(Student s, CurrentUser me) {
        return s.getAssignedCounsellor() != null && s.getAssignedCounsellor().getId().equals(me.id());
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] readChecked(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a file to upload");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("Files can be at most 10 MB");
        }
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }
    }

    /** Decides the type from the file's own bytes, never from the name or the browser's claim. */
    static String sniff(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') {
            return "application/pdf";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        return null;
    }

    private static String safeName(String original, String ext) {
        String base = original == null ? "document" : original.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (base.isBlank()) {
            base = "document";
        }
        if (base.length() > 150) {
            base = base.substring(0, 150);
        }
        return base.toLowerCase().endsWith("." + ext) ? base : base + "." + ext;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
