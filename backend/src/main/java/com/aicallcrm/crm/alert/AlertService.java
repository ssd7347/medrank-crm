package com.aicallcrm.crm.alert;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.common.Role;
import com.aicallcrm.crm.user.AppUser;
import com.aicallcrm.crm.user.AppUserRepository;

/**
 * Single entry point for in-app alerts. Joins the caller's transaction, so an alert exists only if the
 * change that caused it committed, and every alert carries a dedup key so a repeat never doubles up.
 */
@Service
public class AlertService {

    private final NotificationRepository notifications;
    private final AppUserRepository users;

    public AlertService(NotificationRepository notifications, AppUserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    /** Returns 1 if a notification was created, 0 if it already existed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int notifyUser(Long recipientId, Long contactId, String type, Priority priority, String title,
                          String body, String link, String dedupKey) {
        if (dedupKey != null && notifications.existsByRecipientIdAndDedupKey(recipientId, dedupKey)) {
            return 0;
        }
        notifications.save(new Notification(recipientId, contactId, type, priority, truncate(title, 200),
                truncate(body, 1000), link, dedupKey));
        return 1;
    }

    /** The given owner if active, otherwise every active admin and manager. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int notifyOwnerOrManagers(AppUser owner, Long contactId, String type, Priority priority, String title,
                                     String body, String link, String dedupKey) {
        List<Long> recipients = owner != null && owner.isActive() ? List.of(owner.getId())
                : users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN, Role.MANAGER)).stream()
                        .map(AppUser::getId).toList();
        int created = 0;
        for (Long r : recipients) {
            created += notifyUser(r, contactId, type, priority, title, body, link, dedupKey);
        }
        return created;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
