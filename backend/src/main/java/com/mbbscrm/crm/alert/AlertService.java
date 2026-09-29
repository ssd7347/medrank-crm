package com.mbbscrm.crm.alert;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

/**
 * Single entry point for raising alerts. Joins the caller's transaction, so an alert exists only if the
 * business change that caused it committed. Every alert carries a dedup key so re-running a scan, or
 * re-recording the same result, never double-messages a family.
 */
@Service
public class AlertService {

    /** Published after commit to trigger immediate dispatch of urgent messages. */
    public record UrgentQueued() {
    }

    private final NotificationRepository notifications;
    private final OutboundMessageRepository messages;
    private final AppUserRepository users;
    private final ApplicationEventPublisher events;

    public AlertService(NotificationRepository notifications, OutboundMessageRepository messages,
                        AppUserRepository users, ApplicationEventPublisher events) {
        this.notifications = notifications;
        this.messages = messages;
        this.users = users;
        this.events = events;
    }

    /** Returns how many new notifications were created (0 if they already existed). Notifies the student's counsellor, or every active admin if the student has no counsellor. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int notifyStaffFor(Student student, String type, Priority priority, String title, String body,
                               String link, String dedupKey) {
        int created = 0;
        for (Long recipient : staffFor(student)) {
            created += notifyUser(recipient, student.getId(), type, priority, title, body, link, dedupKey);
        }
        return created;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int notifyUser(Long recipientId, Long studentId, String type, Priority priority, String title,
                           String body, String link, String dedupKey) {
        if (dedupKey != null && notifications.existsByRecipientIdAndDedupKey(recipientId, dedupKey)) {
            return 0;
        }
        notifications.save(new Notification(recipientId, studentId, type, priority, truncate(title, 200),
                truncate(body, 1000), link, dedupKey));
        return 1;
    }

    /**
     * Queues a WhatsApp message to the student and, if recorded, the parent. Urgent messages go out right
     * after the transaction commits; normal ones on the next dispatcher sweep.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int messageFamily(Student student, String template, Priority priority, String body, String dedupKey) {
        int queued = 0;
        queued += queue(student, student.getPhone(), "STUDENT", template, priority, body, dedupKey + ":S");
        if (student.getParentPhone() != null && !student.getParentPhone().equals(student.getPhone())) {
            queued += queue(student, student.getParentPhone(), "PARENT", template, priority, body, dedupKey + ":P");
        }
        if (queued > 0 && priority == Priority.URGENT) {
            events.publishEvent(new UrgentQueued());
        }
        return queued;
    }

    private int queue(Student student, String phone, String label, String template, Priority priority, String body,
                      String dedupKey) {
        if (phone == null || phone.isBlank() || messages.existsByDedupKey(dedupKey)) {
            return 0;
        }
        messages.save(new OutboundMessage(student.getId(), Channel.WHATSAPP, phone, label, template, priority, body,
                dedupKey));
        return 1;
    }

    List<Long> staffFor(Student student) {
        AppUser counsellor = student.getAssignedCounsellor();
        if (counsellor != null && counsellor.isActive()) {
            return List.of(counsellor.getId());
        }
        return users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN)).stream()
                .map(AppUser::getId).toList();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
