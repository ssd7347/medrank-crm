package com.mbbscrm.crm.approval;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.approval.DataChangeRequest.Action;
import com.mbbscrm.crm.approval.DataChangeRequest.EntityType;
import com.mbbscrm.crm.approval.DataChangeRequest.Status;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.PageResponse;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.user.UserDtos.UserRef;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Data/Research executives propose changes; a super admin approves them before they go live. Changes an
 * admin submits are applied immediately but still recorded as approved requests, so history is complete.
 */
@Service
public class ChangeRequestService {

    static final EnumSet<Role> SUBMIT_ROLES = EnumSet.of(Role.SUPER_ADMIN, Role.DATA_EXEC);

    private final DataChangeRequestRepository requests;
    private final MasterDataApplier applier;
    private final AppUserRepository users;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ChangeRequestService(DataChangeRequestRepository requests, MasterDataApplier applier,
                                AppUserRepository users, AuditService audit, ObjectMapper mapper) {
        this.requests = requests;
        this.applier = applier;
        this.users = users;
        this.audit = audit;
        this.mapper = mapper;
    }

    public record SubmitRequest(@NotNull EntityType entityType, @NotNull Action action, Long entityId,
                                JsonNode payload) {
    }

    public record ReviewRequest(@Size(max = 500) String note) {
    }

    public record ChangeRequestResponse(Long id, EntityType entityType, Long entityId, Action action, String summary,
                                        Status status, UserRef requestedBy, Instant requestedAt, UserRef reviewedBy,
                                        Instant reviewedAt, String reviewNote) {
        static ChangeRequestResponse of(DataChangeRequest r) {
            return new ChangeRequestResponse(r.getId(), r.getEntityType(), r.getEntityId(), r.getAction(),
                    r.getSummary(), r.getStatus(), UserRef.of(r.getRequestedBy()), r.getRequestedAt(),
                    UserRef.of(r.getReviewedBy()), r.getReviewedAt(), r.getReviewNote());
        }
    }

    /** Full view for the reviewer: the proposed payload next to the record as it is now. */
    public record ChangeRequestDetail(ChangeRequestResponse request, JsonNode payload, Object current) {
    }

    @Transactional
    public ChangeRequestResponse submit(SubmitRequest req) {
        CurrentUser me = requireSubmit();
        MasterDataApplier.Prepared prepared = applier.prepare(req.entityType(), req.action(), req.entityId(),
                req.payload());
        return store(req.entityType(), req.action(), req.entityId(), prepared, me);
    }

    /** Stores an already-prepared change (used by CSV bulk upload). */
    @Transactional
    public ChangeRequestResponse submitPrepared(EntityType type, Action action, Object payload) {
        CurrentUser me = requireSubmit();
        MasterDataApplier.Prepared prepared = applier.prepare(type, action, null, mapper.valueToTree(payload));
        return store(type, action, null, prepared, me);
    }

    private ChangeRequestResponse store(EntityType type, Action action, Long entityId,
                                        MasterDataApplier.Prepared prepared, CurrentUser me) {
        AppUser requester = users.getReferenceById(me.id());
        Long target = action == Action.CREATE || action == Action.BULK_UPSERT ? null : entityId;
        DataChangeRequest r = requests.save(new DataChangeRequest(type, target, action, prepared.summary(),
                prepared.payloadJson(), requester));
        audit.record(me.id(), "CHANGE_REQUESTED", "CHANGE_REQUEST", r.getId(), r.getSummary());
        if (me.isAdmin()) {
            applyAndApprove(r, requester, "Self-approved by admin");
        }
        return ChangeRequestResponse.of(r);
    }

    @Transactional(readOnly = true)
    public PageResponse<ChangeRequestResponse> list(Status status, int page, int size) {
        requireView();
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100),
                Sort.by(Sort.Direction.DESC, "requestedAt"));
        return PageResponse.of(status == null ? requests.findAllBy(pr) : requests.findByStatus(status, pr),
                ChangeRequestResponse::of);
    }

    @Transactional(readOnly = true)
    public ChangeRequestDetail get(Long id) {
        requireView();
        DataChangeRequest r = requests.findById(id).orElseThrow(() -> ApiException.notFound("Change request"));
        return new ChangeRequestDetail(ChangeRequestResponse.of(r), mapper.readTree(r.getPayload()),
                applier.current(r.getEntityType(), r.getEntityId()));
    }

    @Transactional(readOnly = true)
    public List<ChangeRequestResponse> history(EntityType type, Long entityId) {
        requireView();
        return requests.findByEntityTypeAndEntityIdOrderByRequestedAtDesc(type, entityId).stream()
                .map(ChangeRequestResponse::of).toList();
    }

    @Transactional
    public ChangeRequestResponse approve(Long id, String note) {
        CurrentUser me = requireAdmin();
        DataChangeRequest r = loadPending(id);
        applyAndApprove(r, users.getReferenceById(me.id()), note);
        return ChangeRequestResponse.of(r);
    }

    @Transactional
    public ChangeRequestResponse reject(Long id, String note) {
        CurrentUser me = requireAdmin();
        if (note == null || note.isBlank()) {
            throw ApiException.badRequest("Give a reason so the requester knows what to fix");
        }
        DataChangeRequest r = loadPending(id);
        r.review(Status.REJECTED, users.getReferenceById(me.id()), note.trim());
        audit.record(me.id(), "CHANGE_REJECTED", "CHANGE_REQUEST", r.getId(), note.trim());
        return ChangeRequestResponse.of(r);
    }

    private void applyAndApprove(DataChangeRequest r, AppUser reviewer, String note) {
        Long affected = applier.apply(r);
        if (r.getAction() == Action.CREATE) {
            r.setEntityId(affected);
        }
        r.review(Status.APPROVED, reviewer, note == null || note.isBlank() ? null : note.trim());
        audit.record(reviewer.getId(), "CHANGE_APPLIED", r.getEntityType().name(), affected,
                "request #" + r.getId() + ": " + r.getSummary());
    }

    private DataChangeRequest loadPending(Long id) {
        DataChangeRequest r = requests.findById(id).orElseThrow(() -> ApiException.notFound("Change request"));
        if (r.getStatus() != Status.PENDING) {
            throw ApiException.conflict("This request was already " + r.getStatus().name().toLowerCase());
        }
        return r;
    }

    private static CurrentUser requireSubmit() {
        CurrentUser me = CurrentUser.get();
        if (!SUBMIT_ROLES.contains(me.role())) {
            throw ApiException.forbidden("Only Data/Research staff and admins can change college data");
        }
        return me;
    }

    private static CurrentUser requireView() {
        return requireSubmit();
    }

    private static CurrentUser requireAdmin() {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can approve or reject changes");
        }
        return me;
    }
}
