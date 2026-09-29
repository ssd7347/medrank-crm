package com.mbbscrm.crm.alert;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.StudentService;

@RestController
@RequestMapping("/api")
public class AlertController {

    private final NotificationRepository notifications;
    private final OutboundMessageRepository messages;
    private final StudentService students;
    private final AuditService audit;

    public AlertController(NotificationRepository notifications, OutboundMessageRepository messages,
                           StudentService students, AuditService audit) {
        this.notifications = notifications;
        this.messages = messages;
        this.students = students;
        this.audit = audit;
    }

    public record NotificationResponse(Long id, Long studentId, String type, Priority priority, String title,
                                       String body, String link, Instant createdAt, Instant readAt) {
        static NotificationResponse of(Notification n) {
            return new NotificationResponse(n.getId(), n.getStudentId(), n.getType(), n.getPriority(), n.getTitle(),
                    n.getBody(), n.getLink(), n.getCreatedAt(), n.getReadAt());
        }
    }

    public record MessageResponse(Long id, Channel channel, String recipient, String recipientLabel, String template,
                                  Priority priority, String body, MessageStatus status, int attempts,
                                  String lastError, Instant createdAt, Instant sentAt, Instant acknowledgedAt,
                                  Instant escalatedAt) {
        static MessageResponse of(OutboundMessage m) {
            return new MessageResponse(m.getId(), m.getChannel(), m.getRecipient(), m.getRecipientLabel(),
                    m.getTemplate(), m.getPriority(), m.getBody(), m.getStatus(), m.getAttempts(), m.getLastError(),
                    m.getCreatedAt(), m.getSentAt(), m.getAcknowledgedAt(), m.getEscalatedAt());
        }
    }

    public record UnreadCount(long unread) {
    }

    @GetMapping("/notifications")
    @Transactional(readOnly = true)
    public List<NotificationResponse> mine(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                           @RequestParam(defaultValue = "50") int limit) {
        Long me = CurrentUser.get().id();
        PageRequest page = PageRequest.of(0, Math.clamp(limit, 1, 200));
        return (unreadOnly ? notifications.findByRecipientIdAndReadAtIsNullOrderByCreatedAtDesc(me, page)
                : notifications.findByRecipientIdOrderByCreatedAtDesc(me, page))
                .stream().map(NotificationResponse::of).toList();
    }

    @GetMapping("/notifications/unread-count")
    @Transactional(readOnly = true)
    public UnreadCount unreadCount() {
        return new UnreadCount(notifications.countByRecipientIdAndReadAtIsNull(CurrentUser.get().id()));
    }

    @PostMapping("/notifications/{id}/read")
    @Transactional
    public NotificationResponse markRead(@PathVariable Long id) {
        Notification n = notifications.findById(id)
                .filter(x -> x.getRecipientId().equals(CurrentUser.get().id()))
                .orElseThrow(() -> ApiException.notFound("Notification"));
        n.markRead();
        return NotificationResponse.of(n);
    }

    @PostMapping("/notifications/read-all")
    @Transactional
    public UnreadCount markAllRead() {
        notifications.markAllRead(CurrentUser.get().id(), Instant.now());
        return new UnreadCount(0);
    }

    /** Communication log for one student (spec 4.9): every message sent to the student or parent. */
    @GetMapping("/students/{studentId}/messages")
    @Transactional(readOnly = true)
    public List<MessageResponse> studentMessages(@PathVariable Long studentId) {
        students.requireReadable(studentId);
        return messages.findByStudentIdOrderByCreatedAtDesc(studentId, PageRequest.of(0, 200)).stream()
                .map(MessageResponse::of).toList();
    }

    /** Staff confirm the family has seen an urgent message (e.g. they replied or were reached by phone). */
    @PostMapping("/messages/{id}/acknowledge")
    @Transactional
    public MessageResponse acknowledge(@PathVariable Long id) {
        OutboundMessage m = messages.findById(id).orElseThrow(() -> ApiException.notFound("Message"));
        students.requireWritable(m.getStudentId());
        Long me = CurrentUser.get().id();
        m.acknowledge(me);
        audit.record(me, "MESSAGE_ACKNOWLEDGED", "OUTBOUND_MESSAGE", m.getId(), null);
        return MessageResponse.of(m);
    }
}
