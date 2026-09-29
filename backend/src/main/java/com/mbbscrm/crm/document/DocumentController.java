package com.mbbscrm.crm.document;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.document.DocumentService.Checklist;
import com.mbbscrm.crm.document.DocumentService.Download;
import com.mbbscrm.crm.document.DocumentService.Queue;
import com.mbbscrm.crm.document.DocumentService.UpdateRequest;
import com.mbbscrm.crm.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService service;
    private final DocumentTypeRepository types;
    private final AuditService audit;

    public DocumentController(DocumentService service, DocumentTypeRepository types, AuditService audit) {
        this.service = service;
        this.types = types;
        this.audit = audit;
    }

    @GetMapping("/students/{studentId}/documents")
    public Checklist checklist(@PathVariable Long studentId) {
        return service.checklist(studentId);
    }

    @PutMapping("/students/{studentId}/documents/{typeId}")
    public Checklist update(@PathVariable Long studentId, @PathVariable Long typeId, @RequestBody UpdateRequest req) {
        return service.update(studentId, typeId, req);
    }

    @PostMapping("/students/{studentId}/documents/{typeId}/files")
    public Checklist upload(@PathVariable Long studentId, @PathVariable Long typeId,
                            @RequestPart("file") MultipartFile file) {
        return service.upload(studentId, typeId, file);
    }

    /** Streams a stored scan to an authorised user. Never cached, never sniffed by the browser. */
    @GetMapping("/document-files/{fileId}")
    public ResponseEntity<byte[]> download(@PathVariable Long fileId) {
        Download d = service.download(fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(d.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(d.content());
    }

    @GetMapping("/documents/queue")
    public Queue queue() {
        return service.queue();
    }

    // ---- checklist configuration (admin)

    public record TypeRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{2,40}$", message = "letters, digits and _ only") String code,
            @NotBlank @Size(max = 120) String name,
            @NotNull AppliesWhen appliesWhen,
            boolean requiresExpiry,
            @Min(0) @Max(10000) int sortOrder,
            boolean active) {
    }

    public record TypeResponse(Long id, String code, String name, AppliesWhen appliesWhen, boolean requiresExpiry,
                               int sortOrder, boolean active) {
        static TypeResponse of(DocumentType t) {
            return new TypeResponse(t.getId(), t.getCode(), t.getName(), t.getAppliesWhen(), t.isRequiresExpiry(),
                    t.getSortOrder(), t.isActive());
        }
    }

    @GetMapping("/document-types")
    @Transactional(readOnly = true)
    public List<TypeResponse> types() {
        return types.findAllByOrderBySortOrderAscNameAsc().stream().map(TypeResponse::of).toList();
    }

    @PostMapping("/document-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public TypeResponse createType(@Valid @RequestBody TypeRequest req) {
        if (types.existsByCodeIgnoreCase(req.code())) {
            throw ApiException.conflict("A document type with this code already exists");
        }
        DocumentType t = apply(new DocumentType(), req);
        types.save(t);
        audit.record(CurrentUser.get().id(), "DOCUMENT_TYPE_CREATED", "DOCUMENT_TYPE", t.getId(), t.getCode());
        return TypeResponse.of(t);
    }

    @PutMapping("/document-types/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Transactional
    public TypeResponse updateType(@PathVariable Long id, @Valid @RequestBody TypeRequest req) {
        DocumentType t = types.findById(id).orElseThrow(() -> ApiException.notFound("Document type"));
        if (!t.getCode().equalsIgnoreCase(req.code()) && types.existsByCodeIgnoreCase(req.code())) {
            throw ApiException.conflict("A document type with this code already exists");
        }
        apply(t, req);
        audit.record(CurrentUser.get().id(), "DOCUMENT_TYPE_UPDATED", "DOCUMENT_TYPE", t.getId(), t.getCode());
        return TypeResponse.of(t);
    }

    private static DocumentType apply(DocumentType t, TypeRequest req) {
        t.setCode(req.code().trim().toUpperCase(Locale.ROOT));
        t.setName(req.name().trim());
        t.setAppliesWhen(req.appliesWhen());
        t.setRequiresExpiry(req.requiresExpiry());
        t.setSortOrder(req.sortOrder());
        t.setActive(req.active());
        return t;
    }
}
