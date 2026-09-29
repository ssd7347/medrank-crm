package com.mbbscrm.crm.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentRepository;

/**
 * Spec 4.9 escalation rule: if a family has not acknowledged an urgent message within the configured time,
 * the assigned counsellor is told to call them directly.
 */
@Component
public class EscalationJob {

    private static final Map<String, String> CHANNEL_LABELS = Map.of("STUDENT", "student", "PARENT", "parent");

    private final OutboundMessageRepository messages;
    private final StudentRepository students;
    private final AlertService alerts;
    private final Duration escalateAfter;

    public EscalationJob(OutboundMessageRepository messages, StudentRepository students, AlertService alerts,
                         @Value("${app.alerts.escalate-after:PT2H}") Duration escalateAfter) {
        this.messages = messages;
        this.students = students;
        this.alerts = alerts;
        this.escalateAfter = escalateAfter;
    }

    @Scheduled(initialDelayString = "${app.alerts.scan-interval:PT10M}",
            fixedDelayString = "${app.alerts.scan-interval:PT10M}")
    @Transactional
    public int run() {
        int escalated = 0;
        for (OutboundMessage m : messages.findUnacknowledgedUrgent(Instant.now().minus(escalateAfter))) {
            Student s = students.findById(m.getStudentId()).orElse(null);
            m.markEscalated();
            if (s == null) {
                continue;
            }
            String who = CHANNEL_LABELS.getOrDefault(m.getRecipientLabel(), "family");
            alerts.notifyStaffFor(s, "ESCALATION", Priority.URGENT,
                    "Call " + s.getFullName() + "'s " + who + " now",
                    "No acknowledgement " + escalateAfter.toHours() + "h after an urgent message: \"" + m.getBody()
                            + "\". Call " + m.getRecipient() + " directly and mark the message acknowledged.",
                    "/students/" + s.getId() + "?tab=messages", "ESC:" + m.getId());
            escalated++;
        }
        return escalated;
    }
}
