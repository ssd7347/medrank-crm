package com.aicallcrm.crm.audit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.common.PageResponse;
import com.aicallcrm.crm.security.CurrentUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/**
 * The audit log for the admin to read: who did what to which record, newest first, filtered by action, staff
 * member, contact and dates (Indian time). Read only; nothing here changes or removes an entry.
 *
 * <p>The query is built from the filters given, rather than with "(:x is null or ...)", which PostgreSQL cannot
 * type when the value is null.
 */
@Service
public class AuditQueryService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final int MAX_SIZE = 200;

    /** One entry. {@code by} is the staff member's name, or null when the CRM itself acted (an AI call, a login try). */
    public record Entry(Long id, Instant at, String action, Long byId, String by, String entityType, Long entityId,
                        String details, String link) {
    }

    private final EntityManager em;

    public AuditQueryService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public PageResponse<Entry> search(String action, Long byId, Long contactId, LocalDate from, LocalDate to,
                                      int page, int size) {
        admin();
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw ApiException.badRequest("page must be 0 or more and size between 1 and " + MAX_SIZE);
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("The end date is before the start date");
        }
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (action != null && !action.isBlank()) {
            where.append(" and a.action = :action");
            params.put("action", action.trim());
        }
        if (byId != null) {
            where.append(" and a.actorId = :byId");
            params.put("byId", byId);
        }
        if (contactId != null) {
            where.append(" and a.entityType = 'CONTACT' and a.entityId = :contactId");
            params.put("contactId", contactId);
        }
        if (from != null) {
            where.append(" and a.createdAt >= :from");
            params.put("from", from.atStartOfDay(IST).toInstant());
        }
        if (to != null) {
            where.append(" and a.createdAt < :to");
            params.put("to", to.plusDays(1).atStartOfDay(IST).toInstant());
        }
        TypedQuery<Long> count = em.createQuery("select count(a) from AuditLog a" + where, Long.class);
        TypedQuery<AuditLog> rows = em.createQuery("select a from AuditLog a" + where
                + " order by a.createdAt desc, a.id desc", AuditLog.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            rows.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<AuditLog> found = rows.setFirstResult(page * size).setMaxResults(size).getResultList();

        Set<Long> actorIds = new HashSet<>();
        Set<Long> contactIds = new HashSet<>();
        for (AuditLog a : found) {
            if (a.getActorId() != null) {
                actorIds.add(a.getActorId());
            }
            if ("CONTACT".equals(a.getEntityType()) && a.getEntityId() != null) {
                contactIds.add(a.getEntityId());
            }
        }
        Map<Long, String> names = new HashMap<>();
        if (!actorIds.isEmpty()) {
            for (Object[] r : em.createQuery("select u.id, u.fullName from AppUser u where u.id in :ids", Object[].class)
                    .setParameter("ids", actorIds).getResultList()) {
                names.put((Long) r[0], (String) r[1]);
            }
        }
        // A link only to a contact that is still there (an erased one is gone).
        Set<Long> contactsThere = contactIds.isEmpty() ? Set.of() : new HashSet<>(em.createQuery(
                        "select c.id from Contact c where c.id in :ids", Long.class)
                .setParameter("ids", contactIds).getResultList());
        List<Entry> items = new ArrayList<>();
        for (AuditLog a : found) {
            items.add(new Entry(a.getId(), a.getCreatedAt(), a.getAction(), a.getActorId(), names.get(a.getActorId()),
                    a.getEntityType(), a.getEntityId(), a.getDetails(), link(a, contactsThere)));
        }
        int pages = (int) ((total + size - 1) / size);
        return new PageResponse<>(items, page, size, total, pages);
    }

    /** The actions that appear in the log, for the filter. */
    @Transactional(readOnly = true)
    public List<String> actions() {
        admin();
        return em.createQuery("select distinct a.action from AuditLog a order by a.action", String.class).getResultList();
    }

    private static String link(AuditLog a, Set<Long> contactsThere) {
        if ("CONTACT".equals(a.getEntityType()) && contactsThere.contains(a.getEntityId())) {
            return "/contacts/" + a.getEntityId();
        }
        // Viewing or flagging an AI call records the call's id in the details.
        if ("VOICE_CALL".equals(a.getEntityType()) && a.getDetails() != null) {
            try {
                return "/voice/calls/" + UUID.fromString(a.getDetails().trim());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static void admin() {
        if (!CurrentUser.get().isAdmin()) {
            throw ApiException.forbidden("Only the admin can read the audit log");
        }
    }

    @RestController
    static class AuditController {

        private final AuditQueryService service;

        AuditController(AuditQueryService service) {
            this.service = service;
        }

        @GetMapping("/api/audit")
        public PageResponse<Entry> search(@RequestParam(required = false) String action,
                                          @RequestParam(required = false) Long by,
                                          @RequestParam(required = false) Long contact,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
            return service.search(action, by, contact, from, to, page, size);
        }

        @GetMapping("/api/audit/actions")
        public List<String> actions() {
            return service.actions();
        }
    }
}
