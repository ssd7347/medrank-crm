package com.mbbscrm.crm.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** Joins the caller's transaction so the audit entry commits or rolls back with the change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long actorId, String action, String entityType, Long entityId, String details) {
        repository.save(new AuditLog(actorId, action, entityType, entityId, details));
    }

    /** For events outside any business transaction, such as failed logins. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStandalone(Long actorId, String action, String entityType, Long entityId, String details) {
        repository.save(new AuditLog(actorId, action, entityType, entityId, details));
    }
}
