package com.aicallcrm.crm.alert;

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

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.security.CurrentUser;

@RestController
@RequestMapping("/api")
public class AlertController {

    private final NotificationRepository notifications;
    public AlertController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public record NotificationResponse(Long id, Long contactId, String type, Priority priority, String title,
                                       String body, String link, Instant createdAt, Instant readAt) {
        static NotificationResponse of(Notification n) {
            return new NotificationResponse(n.getId(), n.getContactId(), n.getType(), n.getPriority(), n.getTitle(),
                    n.getBody(), n.getLink(), n.getCreatedAt(), n.getReadAt());
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

}
